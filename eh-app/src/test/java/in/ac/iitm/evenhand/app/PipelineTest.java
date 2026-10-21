package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Exposure;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.ingest.PseudonymKey;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.outcome.Outcome;
import in.ac.iitm.evenhand.report.HtmlReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineTest {

    /** 39 no-effect worlds: the fewest for which p can fall below 0.05 at all (1/40). */
    private static final Settings QUICK = Settings.defaults().withDraws(40, 39);

    private static Pipeline.Run run(CourseSimulator.SimulatedCourse c, CourseConfig config) throws IOException {
        return Pipeline.run(new BufferedReader(new StringReader(c.marksCsv())),
                Pseudonymiser.withSalt(new byte[32]), config, QUICK);
    }

    private static CourseConfig declared(CourseSimulator.SimulatedCourse c) {
        return new CourseConfig(c.name(), c.weights(), CourseConfig.parseRule("relative"), true);
    }

    @Test
    @DisplayName("end to end: a stakeholder-shaped course goes in, a report with a re-check list comes out")
    void endToEnd() throws IOException {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.5);

        Pipeline.Run r = run(course, declared(course));

        assertThat(r.verdict()).isInstanceOf(AuditVerdict.Estimable.class);
        assertThat(r.model()).isPresent();
        assertThat(r.outcome().orElseThrow().shortlist()).isInstanceOf(Outcome.Emitted.class);
        String html = HtmlReport.instructor(r.report());
        assertThat(html).contains("Scripts worth a second look");
    }

    @Test
    @DisplayName("no roll number and no TA name reaches either report")
    void reportsCarryNoIdentifiers() throws IOException {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.5);

        Pipeline.Run r = run(course, declared(course));
        String both = HtmlReport.instructor(r.report()) + HtmlReport.committee(r.report());

        for (CourseSimulator.Row row : course.rows()) {
            assertThat(both).doesNotContain(row.roll());
            assertThat(both).doesNotContain(row.assistant());
        }
    }

    @Test
    @DisplayName("one marker per question: safe by design, nothing estimated, no list")
    void questionWiseIsSafeByDesign() throws IOException {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.SMALL_ELECTIVE, 1, 0.5);

        Pipeline.Run r = run(course, declared(course));

        assertThat(r.verdict().permitsEstimation()).isFalse();
        assertThat(r.verdict().exposure().status()).isEqualTo(Exposure.Status.NONE);
        assertThat(r.model()).isEmpty();
    }

    @Test
    @DisplayName("piles that follow the department code: estimated, but no re-check list is offered")
    void departmentPilesAreRefused() throws IOException {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.LAB_BATCHES, 1, 0.5);

        Pipeline.Run r = run(course, declared(course));

        assertThat(r.verdict()).isInstanceOf(AuditVerdict.EstimableUnderExchangeability.class);
        assertThat(r.outcome().orElseThrow().shortlist()).isInstanceOfSatisfying(Outcome.Refused.class,
                ref -> assertThat(ref.reason()).contains("department"));
    }

    @Test
    @DisplayName("a weight for an assessment that is not in the file is an error, not a silent zero")
    void misnamedWeightIsRejected() {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3);
        CourseConfig wrong = new CourseConfig("x", Map.of("Quiz 1", 25.0, "Quiz 2", 25.0, "Endsem", 50.0),
                CourseConfig.parseRule("relative"), true);

        assertThatThrownBy(() -> run(course, wrong)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("End-sem");
    }

    @Test
    @DisplayName("rules parse from the command line: relative, sigma-only and absolute")
    void parsesRules() {
        assertThat(CourseConfig.parseRule("relative").topFraction()).isEqualTo(0.10);
        assertThat(CourseConfig.parseRule("relative-sigma").topFraction()).isZero();
        assertThat(CourseConfig.parseRule("absolute:S=90,A=80,B=70,C=60,D=50,E=40").absolute()).hasSize(6);
        assertThatThrownBy(() -> CourseConfig.parseRule("curve")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the in-memory key maps pseudonyms back for the terminal, and only there")
    void keyLookup() {
        PseudonymKey key = PseudonymKey.of("# header\nsalt,00\nidentifier,label,digest\nCE23B092,Student 4,ab12\nTA01,Assistant 2,cd34\n");

        assertThat(key.real("Student 4")).isEqualTo("CE23B092");
        assertThat(key.real("Assistant 2")).isEqualTo("TA01");
        assertThat(key.real("Student 99")).isEqualTo("?");
    }
}
