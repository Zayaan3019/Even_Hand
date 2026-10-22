package in.ac.iitm.evenhand.app;

import com.sun.net.httpserver.HttpServer;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class LocalServerTest {

    private static HttpServer server;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws IOException {
        server = LocalServer.start(0);   // any free port
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /** Runs an analysis the way the page does: start it, then poll until it finishes. */
    private static String analyse(String query, String csv) throws Exception {
        HttpResponse<String> started = post("/analyse" + query, csv);
        assertThat(started.statusCode()).isEqualTo(200);
        Matcher m = Pattern.compile("\"job\":\"([0-9a-f]+)\"").matcher(started.body());
        assertThat(m.find()).isTrue();
        for (int i = 0; i < 600; i++) {
            String body = get("/job?id=" + m.group(1)).body();
            if (!body.contains("\"state\":\"running\"")) return body;
            Thread.sleep(100);
        }
        throw new AssertionError("analysis did not finish");
    }

    private static String csv() {
        return CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.4).marksCsv();
    }

    private static final String WEIGHTS =
            "?weights=" + URLEncoder.encode("Quiz 1=25,Quiz 2=25,End-sem=50", StandardCharsets.UTF_8);

    @Test
    @DisplayName("listens on this computer only")
    void loopbackOnly() {
        assertThat(server.getAddress().getAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    @DisplayName("the web app is served, and nothing that is not part of the product")
    void pages() throws Exception {
        HttpResponse<String> page = get("/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<div id=\"root\">", "Even Hand");
        assertThat(page.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(get("/lab").statusCode()).isEqualTo(404);
        assertThat(get("/sample").statusCode()).isEqualTo(404);
        assertThat(get("/nowhere").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the app's own scripts and styles are served, cached by their hashed names")
    void assets() throws Exception {
        Matcher m = Pattern.compile("/assets/[A-Za-z0-9._-]+\\.js").matcher(get("/").body());
        assertThat(m.find()).as("the page links its script").isTrue();
        HttpResponse<String> js = get(m.group());
        assertThat(js.statusCode()).isEqualTo(200);
        assertThat(js.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("text/javascript"));
        assertThat(js.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("immutable"));
    }

    @Test
    @DisplayName("every response carries the security headers: own files only, no framing by other sites")
    void securityHeaders() throws Exception {
        HttpResponse<String> page = get("/");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v ->
                assertThat(v).contains("default-src 'self'", "script-src 'self'", "connect-src 'self'", "object-src 'none'"));
        assertThat(page.headers().firstValue("X-Frame-Options")).hasValue("SAMEORIGIN");
        assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(get("/status").headers().firstValue("Content-Security-Policy")).isPresent();
    }

    @Test
    @DisplayName("paths that try to leave the app's folder are refused")
    void noPathTraversal() throws Exception {
        for (String bad : new String[] {"/assets/../upload.html", "/assets/..%2f..%2fpom.xml", "/assets/%2e%2e/x.js",
                "/assets/", "/assets/a\\b.js"}) {
            try {
                assertThat(get(bad).statusCode()).as(bad).isEqualTo(404);
            } catch (IllegalArgumentException rejectedByClient) {
                // the URI itself is invalid: it never reaches the server, which is just as safe
            }
        }
    }

    @Test
    @DisplayName("an analysis runs as a job: the page polls it and collects the structured result and both reports")
    void analysisJob() throws Exception {
        String body = analyse(WEIGHTS, csv());

        assertThat(body).contains("\"state\":\"done\"", "\"course\":", "\"verdict\":", "\"tas\":", "\"outcome\":",
                "\"bottomLineHtml\":", "\"instructorHtml\":", "\"committeeHtml\":", "\"manifest\":", "Bottom line");
    }

    @Test
    @DisplayName("the same file analysed twice gives the same pseudonyms and the same figures, to the last digit")
    void analysisReproduces() throws Exception {
        String csv = csv();
        // Only the time of the run may differ.
        String first = analyse(WEIGHTS, csv).replaceAll("\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z", "TIME");
        String second = analyse(WEIGHTS, csv).replaceAll("\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z", "TIME");

        assertThat(first).contains("\"state\":\"done\"");
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("names reach the screen beside the reports, but never inside a report")
    void analyseKeepsNamesOutOfTheReports() throws Exception {
        String body = analyse(WEIGHTS, csv());
        String reports = body.substring(0, body.indexOf("\"names\":"));
        String names = body.substring(body.indexOf("\"names\":"));

        assertThat(reports).contains("Bottom line").doesNotContainPattern("[A-Z]{2}\\d{2}B\\d{3}");
        assertThat(names).containsPattern("\"Student \\d+\":\"[A-Z]{2}\\d{2}B\\d{3}\"");
    }

    @Test
    @DisplayName("a weight for an assessment that is not in the file fails the job with a plain reason")
    void badWeightsAreExplained() throws Exception {
        String body = analyse("?weights=Nope%3D1", csv());

        assertThat(body).contains("\"state\":\"failed\"", "no weight given for assessment");
    }

    @Test
    @DisplayName("an unknown job is a plain 400, not a crash")
    void unknownJob() throws Exception {
        HttpResponse<String> r = get("/job?id=deadbeef");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("run it again");
    }

    @Test
    @DisplayName("a file is described before it is analysed: counts, answers and the assessments to weight")
    void inspect() throws Exception {
        HttpResponse<String> r = post("/inspect", csv());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"assessments\":[\"Quiz 1\",\"Quiz 2\",\"End-sem\"]", "\"students\":",
                "\"answers\":0", "\"findings\":").doesNotContainPattern("[A-Z]{2}\\d{2}B\\d{3}");
    }

    @Test
    @DisplayName("an unreadable file comes back as a plain sentence saying how to fix it")
    void inspectExplainsAProblem() throws Exception {
        HttpResponse<String> r = post("/inspect", "roll_no,ta,Q1,Q2\nCE26B001,Ravi,7,8\n");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).startsWith("{\"error\":").contains("Q1 (10)");
    }

    @Test
    @DisplayName("the template is a header only, so nothing in it can pass for a student's marks")
    void template() throws Exception {
        HttpResponse<String> template = get("/template.csv");
        assertThat(template.statusCode()).isEqualTo(200);
        assertThat(template.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("attachment"));
        assertThat(template.body().strip().lines().count()).isEqualTo(1);
        assertThat(template.body()).contains("Q1 (10)", "Q1 answer");
    }

    @Test
    @DisplayName("the status says which answer-reading model is installed")
    void status() throws Exception {
        assertThat(get("/status").body()).contains("\"embedder\":", "\"semantic\":");
    }

    @Test
    @DisplayName("another website cannot post a file here or read a report back (Origin check)")
    void otherOriginsAreRefused() throws Exception {
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(uri("/inspect"))
                .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString(csv())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);

        HttpResponse<String> own = CLIENT.send(HttpRequest.newBuilder(uri("/inspect"))
                .header("Origin", "http://127.0.0.1:" + server.getAddress().getPort())
                .POST(HttpRequest.BodyPublishers.ofString(csv())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(own.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a request addressed to another host name is refused (DNS rebinding)")
    void otherHostsAreRefused() throws Exception {
        try (Socket s = new Socket("127.0.0.1", server.getAddress().getPort())) {
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: attacker.example:" + server.getAddress().getPort()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String response = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 403");
        }
    }
}
