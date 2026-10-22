package in.ac.iitm.evenhand.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.embed.Embedders;
import in.ac.iitm.evenhand.ingest.CsvIngest;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.ingest.XlsxReader;
import in.ac.iitm.evenhand.report.HtmlReport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The instructor's way in: a page in their own browser, served from their own machine.
 *
 * <p>Bound to the loopback address only, so nothing on the network can reach it, and it makes
 * no outbound call. Two further guards, because any web page the instructor has open can also
 * send requests to 127.0.0.1: the {@code Host} header must name this server (which defeats DNS
 * rebinding), and a request that changes or reads course data must come from this server's own
 * page ({@code Origin} check), so another site cannot post a file here or read a report back.
 *
 * <p>An analysis runs as a background job the page polls for progress, because reading a large
 * course's answers takes a while. Nothing is written to disk: results stay in memory for the
 * page to collect, and the last few jobs are kept.
 *
 * <p>This is the only class allowed to touch {@code java.net}, and only to listen on 127.0.0.1
 * ({@code ArchitectureTest}).
 */
public final class LocalServer {

    static final int DEFAULT_PORT = 8642;
    private static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
    private static final int KEPT_JOBS = 4;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "even-hand-analysis");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final List<String> ORDER = new ArrayList<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One analysis: where it has got to, and its result or error once finished. */
    static final class Job {
        volatile String stage = "Waiting to start";
        volatile double fraction;
        volatile String result;
        volatile String error;
    }

    private LocalServer() {
    }

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", ex -> handle(ex, server.getAddress().getPort()));
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "even-hand-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return server;
    }

    private static void handle(HttpExchange ex, int port) throws IOException {
        try {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                send(ex, 403, "text/plain", "Even Hand only answers this computer.");
                return;
            }
            if (!hostIsUs(ex, port)) {
                send(ex, 403, "text/plain", "Even Hand only answers requests addressed to 127.0.0.1:" + port + ".");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            boolean get = "GET".equals(method), post = "POST".equals(method);
            if ((post || path.startsWith("/job")) && !fromOurPage(ex, port)) {
                send(ex, 403, "text/plain", "Requests that carry course data must come from the Even Hand page itself.");
                return;
            }
            if (get && (path.equals("/") || path.equals("/index.html"))) {
                sendStatic(ex, "index.html");
            } else if (get && path.startsWith("/assets/")) {
                sendStatic(ex, path.substring(1));
            } else if (get && path.equals("/template.csv")) {
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"even-hand-template.csv\"");
                send(ex, 200, "text/csv", TEMPLATE);
            } else if (get && path.equals("/status")) {
                send(ex, 200, "application/json", status());
            } else if (post && path.equals("/inspect")) {
                send(ex, 200, "application/json", inspect(readBody(ex.getRequestBody())));
            } else if (post && path.equals("/analyse")) {
                send(ex, 200, "application/json", startJob(query(ex.getRequestURI().getRawQuery()),
                        readBody(ex.getRequestBody())));
            } else if (get && path.equals("/job")) {
                send(ex, 200, "application/json", job(param(query(ex.getRequestURI().getRawQuery()), "id", "")));
            } else {
                send(ex, 404, "text/plain", "Not found.");
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            send(ex, 400, "application/json", new Json().str("error", plain(e)).toString());
        } catch (RuntimeException | Error e) {
            // Every request gets an answer: the JDK server would otherwise drop the connection silently.
            e.printStackTrace();
            send(ex, 500, "application/json", new Json().str("error", "Even Hand hit an internal error: " + e).toString());
        } finally {
            ex.close();
        }
    }

    /** The Host header names this server: 127.0.0.1, localhost or [::1], on our port. */
    static boolean hostIsUs(HttpExchange ex, int port) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) return true;   // HTTP/1.0 clients on this machine
        String h = host.strip().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port) || h.equals("[::1]:" + port);
    }

    /** No Origin (a same-origin GET, or a tool on this machine) or our own origin. */
    static boolean fromOurPage(HttpExchange ex, int port) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String o = origin.strip().toLowerCase(Locale.ROOT);
        return o.equals("http://127.0.0.1:" + port) || o.equals("http://localhost:" + port) || o.equals("http://[::1]:" + port);
    }

    /** Which answer-reading model is installed, for the page's header. */
    static String status() {
        Embedders.Choice c = Embedders.best();
        return new Json().str("embedder", c.embedder().id()).bool("semantic", c.embedder().semantic())
                .str("note", c.note()).str("kernel", Embedders.kernel()).str("version", Pipeline.version()).toString();
    }

    /** Queues an analysis and returns its id at once; the page polls {@code /job}. */
    static String startJob(Map<String, String> q, String csv) {
        String rule = param(q, "rule", "");
        CourseConfig config = new CourseConfig(param(q, "name", "Course"),
                CourseConfig.parseWeights(param(q, "weights", "")),
                CourseConfig.parseRule(rule.isEmpty() ? "relative" : rule),
                !rule.isEmpty());
        String id = HexFormat.of().formatHex(RANDOM.generateSeed(12));
        Job job = new Job();
        synchronized (ORDER) {
            JOBS.put(id, job);
            ORDER.add(id);
            while (ORDER.size() > KEPT_JOBS) JOBS.remove(ORDER.remove(0));
        }
        WORKER.submit(() -> {
            try {
                Pipeline.Run run = Pipeline.run(csv, Pseudonymiser.forInput(csv), config, Pipeline.Options.defaults(),
                        (stage, fraction) -> {
                            job.stage = stage;
                            job.fraction = fraction;
                        });
                job.result = ResultJson.of(run);
                job.fraction = 1;
                job.stage = "Done";
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                job.error = plain(e);
            } catch (Exception | Error e) {
                e.printStackTrace();
                job.error = "Even Hand hit an internal error: " + e;
            }
        });
        return new Json().str("job", id).toString();
    }

    static String job(String id) {
        Job job = JOBS.get(id);
        if (job == null) throw new IllegalArgumentException("That analysis is no longer held; please run it again.");
        Json j = new Json().str("stage", job.stage).num("fraction", job.fraction);
        if (job.error != null) return j.str("state", "failed").str("error", job.error).toString();
        if (job.result != null) return j.str("state", "done").raw("result", job.result).toString();
        return j.str("state", "running").toString();
    }

    /**
     * What the file holds, before anything is analysed: counts, assessment names (so the page can
     * ask for their weights), how many answers carry text, and what reading it found. Nothing
     * identifying is returned.
     */
    static String inspect(String csv) throws IOException {
        CsvIngest.IngestResult in = CsvIngest.read(new BufferedReader(new StringReader(csv)),
                Pseudonymiser.forInput(csv));
        List<String> assessments = new ArrayList<>();
        for (String a : in.assessmentLabels()) assessments.add(Json.quote(a));
        Map<String, List<Diagnostic>> byCode = new LinkedHashMap<>();
        for (Diagnostic d : in.diagnostics()) byCode.computeIfAbsent(HtmlReport.kindOf(d), k -> new ArrayList<>()).add(d);
        List<String> findings = new ArrayList<>();
        for (List<Diagnostic> group : byCode.values()) {
            Diagnostic first = group.get(0);
            findings.add(new Json()
                    .str("severity", first.severity().name().toLowerCase(Locale.ROOT))
                    .str("message", first.message())
                    .str("fix", first.fixIt().orElse(""))
                    .num("more", group.size() - 1).toString());
        }
        return new Json()
                .num("rows", in.responses().size())
                .num("students", in.design().studentCount())
                .num("assistants", in.design().graderCount())
                .num("questions", in.design().questionCount())
                .num("answers", in.answerCount())
                .num("keys", in.references().size())
                .raw("assessments", Json.array(assessments))
                .raw("findings", Json.array(findings))
                .toString();
    }

    /** The error message alone, without the exception's class name. */
    private static String plain(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** Where the web app's built files live on the classpath (eh-web). */
    static final String WEB_ROOT = "/web/";

    /** Only plain relative paths: letters, digits, dot, dash, underscore and slash, and never "..". */
    private static final java.util.regex.Pattern SAFE_PATH = java.util.regex.Pattern.compile("[A-Za-z0-9._/-]{1,200}");

    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html", "js", "text/javascript", "css", "text/css", "svg", "image/svg+xml",
            "png", "image/png", "ico", "image/x-icon", "json", "application/json", "woff2", "font/woff2", "map", "application/json");

    /**
     * A file of the web app. Hashed assets are cached for a year (their name changes with their
     * content); the page itself is never cached, so a new version is picked up at once.
     */
    private static void sendStatic(HttpExchange ex, String relative) throws IOException {
        // Files only, of the kinds the app is made of: never a folder (which a classpath may list) or "..".
        int dot = relative.lastIndexOf('.');
        String ext = dot < 0 ? "" : relative.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SAFE_PATH.matcher(relative).matches() || relative.contains("..") || relative.endsWith("/")
                || dot < relative.lastIndexOf('/') || !TYPES.containsKey(ext)) {
            send(ex, 404, "text/plain", "Not found.");
            return;
        }
        byte[] body;
        try (InputStream in = LocalServer.class.getResourceAsStream(WEB_ROOT + relative)) {
            if (in == null) {
                send(ex, 404, "text/plain", relative.equals("index.html")
                        ? "The web app is missing from this build. Rebuild with ./mvnw package." : "Not found.");
                return;
            }
            body = in.readAllBytes();
        }
        String type = TYPES.getOrDefault(ext, "application/octet-stream");
        ex.getResponseHeaders().set("Content-Type", type + (type.startsWith("text/") || type.endsWith("json")
                || type.endsWith("xml") ? "; charset=utf-8" : ""));
        ex.getResponseHeaders().set("Cache-Control", relative.startsWith("assets/") ? "public, max-age=31536000, immutable"
                : "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Headers on every response. The content security policy lets the page load only its own files and
     * talk only to this server: even a bug that let text from a marks file reach the page as markup could
     * not load or send anything elsewhere.
     */
    private static void securityHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; "
                + "frame-src 'self' blob: about:; object-src 'none'; base-uri 'none'; form-action 'none'");
    }

    /** The uploaded file as CSV text: a CSV as sent, an Excel workbook's first sheet converted. */
    private static String readBody(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_UPLOAD_BYTES + 1);
        if (bytes.length > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("The file is larger than 50 MB.");
        if (XlsxReader.looksLikeXlsx(bytes)) return XlsxReader.toCsv(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** A form field, or the fallback when it is absent or blank. */
    static String param(Map<String, String> q, String key, String fallback) {
        String v = q.get(key);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        securityHeaders(ex);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * A blank mark sheet in the shape most instructors already keep, ready to fill in Excel: the
     * header only, so nothing in it can be mistaken for a student's marks.
     */
    static final String TEMPLATE = "roll_no,name,ta,Q1 (10),Q1 answer,Q2 (10),Q2 answer,Q3 (15),Q3 answer\n";
}
