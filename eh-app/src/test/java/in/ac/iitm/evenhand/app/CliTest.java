package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliTest {

    @Test
    @DisplayName("run: the instructor's two reports appear, with no roll number in either")
    void run(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("course.csv");
        CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3).writeMarksCsv(csv);

        Main.run(csv, Map.of("weights", "Quiz 1=25,Quiz 2=25,End-sem=50", "out", dir.resolve("out").toString()));

        String report = Files.readString(dir.resolve("out/report.html"));
        String committee = Files.readString(dir.resolve("out/committee.html"));
        assertThat(report).contains("Even Hand", "Scripts worth a second look");
        assertThat(committee).contains("Class Committee sheet");
        assertThat(report + committee).doesNotContainPattern("CE23B\\d{3}");
        assertThat(dir.resolve("out/course.key")).doesNotExist();
    }

    @Test
    @DisplayName("flags are read as --name value pairs after the positional arguments")
    void flags() {
        Map<String, String> f = Main.flags(new String[] { "run", "marks.csv", "--rule", "relative", "--out", "x" });

        assertThat(f).containsEntry("rule", "relative").containsEntry("out", "x").hasSize(2);
    }
}
