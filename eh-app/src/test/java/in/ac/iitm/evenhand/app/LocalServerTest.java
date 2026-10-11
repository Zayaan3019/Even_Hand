package in.ac.iitm.evenhand.app;

import com.sun.net.httpserver.HttpServer;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

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

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    @DisplayName("listens on this computer only")
    void loopbackOnly() {
        assertThat(server.getAddress().getAddress().isLoopbackAddress()).isTrue();
    }

    @Test
    @DisplayName("the upload page and the lab page are served")
    void pages() throws Exception {
        assertThat(get("/").body()).contains("Drop your marks file");
        assertThat(get("/lab").body()).contains("Simulation Lab");
        assertThat(get("/nowhere").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the lab runs the real pipeline and returns truth beside the estimates")
    void labRun() throws Exception {
        HttpResponse<String> r = get("/lab/run?shape=ce-core&spread=0.4&seed=3");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).startsWith("{").contains("\"verdict\":\"ESTIMABLE\"", "\"truth\":", "\"estimate\":",
                "\"recheck\":", "\"allocation\":");
    }

    @Test
    @DisplayName("a refused design comes back with its reason, not an error")
    void labRefusal() throws Exception {
        HttpResponse<String> r = get("/lab/run?shape=small-elective&spread=0.4&seed=3");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"estimated\":false", "\"refusal\":");
    }

    @Test
    @DisplayName("a marks file posted from the page comes back as the instructor's report")
    void report() throws Exception {
        String csv = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.4).marksCsv();
        String q = "?weights=" + URLEncoder.encode("Quiz 1=25,Quiz 2=25,End-sem=50", StandardCharsets.UTF_8);

        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(uri("/report" + q))
                .POST(HttpRequest.BodyPublishers.ofString(csv)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("Scripts worth a second look").doesNotContainPattern("CE23B\\d{3}");
    }

    @Test
    @DisplayName("bad input is explained to the instructor, not thrown at them")
    void badInput() throws Exception {
        assertThat(get("/lab/run?shape=no-such-course").statusCode()).isEqualTo(400);
        HttpResponse<String> r = CLIENT.send(HttpRequest.newBuilder(uri("/report?weights=Nope%3D1"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3).marksCsv())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("could not be analysed");
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a file is described before it is analysed: counts and the assessments to weight")
    void inspect() throws Exception {
        String csv = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.4).marksCsv();

        HttpResponse<String> r = post("/inspect", csv);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"assessments\":[\"Quiz 1\",\"Quiz 2\",\"End-sem\"]", "\"students\":",
                "\"findings\":").doesNotContainPattern("[A-Z]{2}\\d{2}B\\d{3}");
    }

    @Test
    @DisplayName("an unreadable file comes back as a plain sentence saying how to fix it")
    void inspectExplainsAProblem() throws Exception {
        HttpResponse<String> r = post("/inspect", "roll_no,ta,Q1,Q2\nCE26B001,Ravi,7,8\n");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).startsWith("{\"error\":").contains("Q1 (10)");
    }

    @Test
    @DisplayName("names reach the screen beside the reports, but never inside a report")
    void analyseKeepsNamesOutOfTheReports() throws Exception {
        String csv = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.4).marksCsv();
        String q = "?weights=" + URLEncoder.encode("Quiz 1=25,Quiz 2=25,End-sem=50", StandardCharsets.UTF_8);

        HttpResponse<String> r = post("/analyse" + q, csv);

        assertThat(r.statusCode()).isEqualTo(200);
        String body = r.body();
        String reports = body.substring(0, body.indexOf("\"names\":"));
        String names = body.substring(body.indexOf("\"names\":"));
        assertThat(reports).contains("Bottom line").doesNotContainPattern("[A-Z]{2}\\d{2}B\\d{3}");
        assertThat(names).containsPattern("\"Student \\d+\":\"[A-Z]{2}\\d{2}B\\d{3}\"");
    }

    @Test
    @DisplayName("a template and a sample course are one click away")
    void templateAndSample() throws Exception {
        HttpResponse<String> template = get("/template.csv");
        assertThat(template.statusCode()).isEqualTo(200);
        assertThat(template.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("attachment"));
        assertThat(post("/inspect", template.body()).statusCode()).isEqualTo(200);

        HttpResponse<String> sample = get("/sample");
        assertThat(sample.statusCode()).isEqualTo(200);
        assertThat(sample.body()).contains("\"csv\":\"roll_no,", "\"weights\":\"");
    }
}
