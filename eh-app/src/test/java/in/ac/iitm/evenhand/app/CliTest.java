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
    @DisplayName("run: a file with no question maxima is refused with what to do, as a message, not a stack trace")
    void refusedFileSaysWhy(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("broken.csv");
        Files.writeString(csv, "roll_no,ta,Q1,Q2\nCE26B001,Ravi,7,8\nCE26B002,Meena,6,9\n");

        Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> Main.run(csv, Map.of("out", dir.resolve("out").toString())));

        assertThat(Main.userError(e)).contains("marked out of", "Q1 (10)");
        assertThat(Main.userError(new java.nio.file.NoSuchFileException("missing.csv"))).contains("no file at missing.csv");
        assertThat(Main.userError(new NullPointerException())).isNull();
    }

    @Test
    @DisplayName("flags are read as --name value pairs after the positional arguments")
    void flags() {
        Map<String, String> f = Main.flags(new String[] { "run", "marks.csv", "--rule", "relative", "--out", "x" });

        assertThat(f).containsEntry("rule", "relative").containsEntry("out", "x").hasSize(2);
    }

    @Test
    @DisplayName("run: rows the reader could not use are listed in the terminal, never dropped silently")
    void skippedRowsArePrinted() {
        var d = in.ac.iitm.evenhand.core.Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE;
        var list = java.util.List.of(
                in.ac.iitm.evenhand.core.Diagnostic.error(d, "line 3: could not read '7,5' as a mark; row skipped.",
                        java.util.List.of(), "Write decimals with a point: 7.5, not 7,5."),
                in.ac.iitm.evenhand.core.Diagnostic.error(d, "line 3: could not read '7,5' as a mark; row skipped.",
                        java.util.List.of(), "Write decimals with a point: 7.5, not 7,5."),
                in.ac.iitm.evenhand.core.Diagnostic.note(d, "Read as one row per student.", java.util.List.of()));
        java.io.PrintStream was = System.out;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            Main.printSkipped(list);
        } finally {
            System.setOut(was);
        }
        String printed = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(printed).contains("1 line(s) of the file could not be used in full", "line 3", "'7,5'", "7.5, not 7,5")
                .doesNotContain("one row per student");
    }
}
