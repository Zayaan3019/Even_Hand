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
        assertThat(get("/").body()).contains("Marks file (CSV)");
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
}
