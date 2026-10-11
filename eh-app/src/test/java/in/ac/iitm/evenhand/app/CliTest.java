package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import in.ac.iitm.evenhand.estimate.Settings;
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
    @DisplayName("simulate then run: the instructor's two reports appear, with no roll number in either")
    void simulateThenRun(@TempDir Path dir) throws IOException {
        Main.simulate("ce-core", 3, dir);
        Path csv = dir.resolve("ce-core-3.csv");
        assertThat(csv).exists();
        assertThat(dir.resolve("ce-core-3.truth.csv")).exists();

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

    @Test
    @DisplayName("on a stakeholder-shaped course, the re-check list finds more real grade changes than "
            + "re-checking the scripts nearest a boundary")
    void beatsNearestBoundaryOnAStakeholderShapedCourse() {
        CutoffPolicy rule = CutoffPolicy.iitmConventionalRelative();
        Benchmark.Prepared course = Benchmark.prepare(
                CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.4), rule);

        Benchmark.Score score = Benchmark.score(course, Settings.defaults().withDraws(100, 39), rule);

        assertThat(score.flagged()).isPositive();
        assertThat(score.flaggedRight()).isGreaterThan(score.baselineRight());
    }
}
