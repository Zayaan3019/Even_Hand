package in.ac.iitm.evenhand.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.ac.iitm.evenhand.estimate.Settings;
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
import java.util.HashMap;
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
                send(ex, 200, "text/html", PAGE);
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
            send(ex, 400, "text/html", "<!doctype html><meta charset=utf-8><title>Even Hand</title>"
                    + "<body style='font:15px system-ui;max-width:760px;margin:40px auto;padding:0 16px'>"
                    + "<h2>The file could not be analysed</h2><pre style='white-space:pre-wrap'>"
                    + escape(e.getMessage()) + "</pre><p><a href='/'>Back</a></p>");
        } catch (RuntimeException e) {
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
        String csv = readBody(ex.getRequestBody());
        String rule = param(q, "rule", "");
        CourseConfig config = new CourseConfig(param(q, "name", "Course"),
                CourseConfig.parseWeights(param(q, "weights", "")),
                CourseConfig.parseRule(rule.isEmpty() ? "relative" : rule),
                !rule.isEmpty());
        Pipeline.Run run = Pipeline.run(new BufferedReader(new StringReader(csv)), Pseudonymiser.withRandomSalt(),
                config, Settings.defaults());
        String which = param(q, "view", "instructor");
        return which.equals("committee") ? HtmlReport.committee(run.report()) : HtmlReport.instructor(run.report());
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

    private static final String PAGE = """
            <!doctype html><html lang=en><head><meta charset=utf-8>
            <meta name=viewport content="width=device-width,initial-scale=1"><title>Even Hand</title>
            <style>
            :root{--bg:#fbfaf7;--fg:#1d2433;--muted:#5d6678;--line:#d9dce3;--accent:#8a1c1c;--card:#fff}
            @media (prefers-color-scheme:dark){:root{--bg:#14171d;--fg:#e6e8ec;--muted:#a3abba;--line:#323844;--accent:#e08a7a;--card:#1b1f27}}
            body{background:var(--bg);color:var(--fg);font:15px/1.55 system-ui,"Segoe UI",sans-serif;margin:0}
            main{max-width:720px;margin:0 auto;padding:32px 16px}
            h1{margin:0}p.sub{color:var(--muted);margin-top:4px}
            form{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:20px;margin-top:20px}
            label{display:block;font-weight:600;margin:14px 0 4px}small{color:var(--muted);font-weight:400}
            input,select{width:100%;box-sizing:border-box;padding:8px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg);font:inherit}
            button{margin-top:18px;padding:10px 18px;border:0;border-radius:6px;background:var(--accent);color:#fff;font:inherit;font-weight:600;cursor:pointer}
            #status{color:var(--muted);margin-top:10px}a{color:var(--accent);font-weight:600}
            </style></head><body><main>
            <h1>Even Hand</h1>
            <p class=sub>Did it matter which TA marked the script? Runs on this computer only; the marks never leave it.
            New here? Try the <a href="/lab">Simulation Lab</a>: a synthetic course where the truth is visible.</p>
            <form id=f>
              <label>Marks file (CSV) <small>one row per marked answer: roll_no, assessment, question, max_marks, marks, ta</small></label>
              <input type=file id=file accept=".csv,text/csv" required>
              <label>Course name</label><input id=name placeholder="CE3020 Jul-Nov 2026">
              <label>Assessment weights <small>names exactly as in the file; blank means equal</small></label>
              <input id=weights placeholder="Quiz 1=25, Quiz 2=25, End-sem=50">
              <label>Grading rule</label>
              <select id=rule>
                <option value="relative">Relative: B at the mean, A/S at +1/+2 SD, top 10% get S</option>
                <option value="relative-sigma">Relative, without the fixed top-10% S</option>
                <option value="absolute:S=90,A=80,B=70,C=60,D=50,E=40">Absolute: S 90, A 80, B 70, C 60, D 50, E 40</option>
              </select>
              <label>Report for</label>
              <select id=view><option value=instructor>Me (instructor): per-TA figures and re-check list</option>
              <option value=committee>Class Committee: aggregates only</option></select>
              <button>Analyse</button><div id=status></div>
            </form></main>
            <script>
            document.getElementById('f').addEventListener('submit', async e => {
              e.preventDefault();
              const file = document.getElementById('file').files[0];
              const status = document.getElementById('status');
              status.textContent = 'Analysing ' + file.name + ' ...';
              const q = new URLSearchParams({name: document.getElementById('name').value,
                weights: document.getElementById('weights').value, rule: document.getElementById('rule').value,
                view: document.getElementById('view').value});
              const res = await fetch('/report?' + q, {method: 'POST', body: await file.text()});
              document.open(); document.write(await res.text()); document.close();
            });
            </script></body></html>
            """;
}
