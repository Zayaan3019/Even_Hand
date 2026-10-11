package in.ac.iitm.evenhand.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.ingest.CsvIngest;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The instructor's way in: a page in their own browser, served from their own machine.
 *
 * <p>Bound to the loopback address only, so nothing on the network can reach it, and it makes
 * no outbound call. The page reads the CSV in the browser and posts its text here; the marks go
 * from the instructor's disk to this process and nowhere else. Nothing is written to disk: the
 * report comes back as the response, and the instructor saves it if they wish.
 *
 * <p>This is the only class allowed to touch {@code java.net}, and only to listen on 127.0.0.1
 * ({@code ArchitectureTest}).
 */
public final class LocalServer {

    static final int DEFAULT_PORT = 8642;
    private static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

    private LocalServer() {
    }

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", LocalServer::handle);
        server.start();
        return server;
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
                send(ex, 403, "text/plain", "Even Hand only answers this computer.");
                return;
            }
            String path = ex.getRequestURI().getPath();
            if ("GET".equals(ex.getRequestMethod()) && path.equals("/")) {
                send(ex, 200, "text/html", resource("/upload.html"));
            } else if ("GET".equals(ex.getRequestMethod()) && path.equals("/template.csv")) {
                ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"even-hand-template.csv\"");
                send(ex, 200, "text/csv", TEMPLATE);
            } else if ("GET".equals(ex.getRequestMethod()) && path.equals("/sample")) {
                send(ex, 200, "application/json", sample(param(query(ex.getRequestURI().getRawQuery()), "shape",
                        "first-year-core")));
            } else if ("POST".equals(ex.getRequestMethod()) && path.equals("/inspect")) {
                send(ex, 200, "application/json", inspect(readBody(ex.getRequestBody())));
            } else if ("POST".equals(ex.getRequestMethod()) && path.equals("/analyse")) {
                send(ex, 200, "application/json", analyse(ex));
            } else if ("GET".equals(ex.getRequestMethod()) && path.equals("/lab")) {
                send(ex, 200, "text/html", resource("/lab.html"));
            } else if ("GET".equals(ex.getRequestMethod()) && path.equals("/lab/run")) {
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                send(ex, 200, "application/json", SimulationLab.run(q.getOrDefault("shape", "ce-core"),
                        Double.parseDouble(q.getOrDefault("spread", "0.35")), Long.parseLong(q.getOrDefault("seed", "7"))));
            } else if ("POST".equals(ex.getRequestMethod()) && path.equals("/report")) {
                send(ex, 200, "text/html", report(ex));
            } else {
                send(ex, 404, "text/plain", "Not found.");
            }
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/inspect") || path.equals("/analyse")) {
                send(ex, 400, "application/json", new SimulationLab.Json().str("error", plain(e)).toString());
                return;
            }
            send(ex, 400, "text/html", "<!doctype html><meta charset=utf-8><title>Even Hand</title>"
                    + "<body style='font:15px system-ui;max-width:760px;margin:40px auto;padding:0 16px'>"
                    + "<h2>The file could not be analysed</h2><pre style='white-space:pre-wrap'>"
                    + escape(e.getMessage()) + "</pre><p><a href='/'>Back</a></p>");
        } catch (RuntimeException | Error e) {
            // Every request gets an answer: the JDK server would otherwise drop the connection silently.
            e.printStackTrace();
            send(ex, 500, "text/plain", "Even Hand hit an internal error: " + e);
        } finally {
            ex.close();
        }
    }

    /** Runs the whole pipeline on the posted CSV and returns the instructor report. */
    static String report(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        Pipeline.Run run = run(q, readBody(ex.getRequestBody()));
        String which = param(q, "view", "instructor");
        return which.equals("committee") ? HtmlReport.committee(run.report()) : HtmlReport.instructor(run.report());
    }

    /**
     * Both reports, and the pseudonym-to-name table for this run, for the page to show.
     *
     * <p>The table goes only to the browser on this computer, the same place the terminal
     * would show it; it is never part of a report, so a saved report carries pseudonyms only.
     */
    static String analyse(HttpExchange ex) throws IOException {
        Pipeline.Run run = run(query(ex.getRequestURI().getRawQuery()), readBody(ex.getRequestBody()));
        SimulationLab.Json names = new SimulationLab.Json();
        Main.KeyLookup.of(run.ingest().keyFileContents()).realByLabel().forEach(names::str);
        return new SimulationLab.Json()
                .str("instructor", HtmlReport.instructor(run.report()))
                .str("committee", HtmlReport.committee(run.report()))
                .raw("names", names.toString())
                .toString();
    }

    private static Pipeline.Run run(Map<String, String> q, String csv) throws IOException {
        String rule = param(q, "rule", "");
        CourseConfig config = new CourseConfig(param(q, "name", "Course"),
                CourseConfig.parseWeights(param(q, "weights", "")),
                CourseConfig.parseRule(rule.isEmpty() ? "relative" : rule),
                !rule.isEmpty());
        return Pipeline.run(new BufferedReader(new StringReader(csv)), Pseudonymiser.withRandomSalt(),
                config, Settings.defaults());
    }

    /**
     * What the file holds, before anything is analysed: counts, assessment names (so the page can
     * ask for their weights), and what reading it found. Nothing identifying is returned.
     */
    static String inspect(String csv) throws IOException {
        CsvIngest.IngestResult in = CsvIngest.read(new BufferedReader(new StringReader(csv)),
                Pseudonymiser.withRandomSalt());
        StringBuilder assessments = new StringBuilder("[");
        for (String a : in.assessmentLabels()) {
            assessments.append(assessments.length() == 1 ? "" : ",").append(SimulationLab.Json.quote(a));
        }
        Map<Diagnostic.Code, List<Diagnostic>> byCode = new LinkedHashMap<>();
        for (Diagnostic d : in.diagnostics()) byCode.computeIfAbsent(d.code(), k -> new ArrayList<>()).add(d);
        StringBuilder findings = new StringBuilder("[");
        for (List<Diagnostic> group : byCode.values()) {
            Diagnostic first = group.get(0);
            findings.append(findings.length() == 1 ? "" : ",").append(new SimulationLab.Json()
                    .str("severity", first.severity().name().toLowerCase(Locale.ROOT))
                    .str("message", first.message())
                    .str("fix", first.fixIt().orElse(""))
                    .num("more", group.size() - 1));
        }
        return new SimulationLab.Json()
                .num("rows", in.responses().size())
                .num("students", in.design().studentCount())
                .num("assistants", in.design().graderCount())
                .num("questions", in.design().questionCount())
                .raw("assessments", assessments + "]")
                .raw("findings", findings + "]")
                .toString();
    }

    /** A synthetic course to try the tool on, with the weights it was designed with. */
    static String sample(String shape) {
        CourseSimulator.Archetype a = CourseSimulator.Archetype.valueOf(
                shape.toUpperCase(Locale.ROOT).replace('-', '_'));
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(a, 7);
        StringBuilder weights = new StringBuilder();
        course.weights().forEach((k, v) -> weights.append(weights.length() == 0 ? "" : ", ").append(k).append('=')
                .append(v.intValue()));
        return new SimulationLab.Json()
                .str("file", course.name() + ".csv")
                .str("name", "Sample: " + a.description)
                .str("weights", weights.toString())
                .str("csv", course.marksCsv())
                .toString();
    }

    /** The error message alone, without the exception's class name. */
    private static String plain(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = LocalServer.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException("missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_UPLOAD_BYTES + 1);
        if (bytes.length > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("The file is larger than 50 MB.");
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
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A blank mark sheet in the shape most instructors already keep, ready to fill in Excel. */
    static final String TEMPLATE = """
            roll_no,name,ta,Q1 (10),Q2 (10),Q3 (15),Q4 (15)
            CE26B001,Example student,TA name,7,8.5,12,9
            CE26B002,Another student,Another TA,6,AB,10,11
            """;
}
