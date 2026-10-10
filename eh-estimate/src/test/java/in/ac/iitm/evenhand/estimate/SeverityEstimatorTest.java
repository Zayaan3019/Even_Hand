package in.ac.iitm.evenhand.estimate;

import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;
import in.ac.iitm.evenhand.design.IdentifiabilityAudit;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import in.ac.iitm.evenhand.ingest.CsvIngest;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SeverityEstimatorTest {

    @Test
    @DisplayName("noise-free additive marks: backfitting recovers the severities exactly")
    void exactRecoveryOnAdditiveData() {
        // 40 students x 4 questions, each question double-marked by two of three assistants,
        // marks generated exactly from mean + ability + easiness - severity.
        double[] severity = { 6, -2, -4 };            // already averages zero
        double[] easiness = { 10, -5, 0, -5 };
        List<Response> responses = new ArrayList<>();
        int students = 40;
        for (int s = 0; s < students; s++) {
            double ability = (s % 10) - 4.5;
            for (int q = 0; q < 4; q++) {
                for (int g : new int[] { q % 3, (q + 1) % 3 }) {
                    int score = (int) Math.round(10 * (60 + ability + easiness[q] - severity[g]));
                    responses.add(new Response(new DesignCell(s, q, g, 0, DesignCell.NO_POSITION, g == q % 3), score, 1000, 10));
                }
            }
        }
        MarkingDesign design = MarkingDesign.fromResponses(responses, labels("S", students), labels("Q", 4), labels("A", 3));
        Settings noShrink = Settings.defaults().withShrinkage(0, 0).withDraws(0, 0);

        FittedModel m = fit(design, responses, noShrink);

        for (int g = 0; g < 3; g++) assertThat(m.severity(g)).isCloseTo(severity[g], within(0.01));
        assertThat(m.diagnostics().converged()).isTrue();
    }

    @Test
    @DisplayName("a simulated IITM course: every severity within 1.5 points of the truth, and inside its interval")
    void recoversSeveritiesOnARealisticCourse() {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 3, 0.4);
        CsvIngest.IngestResult in = ingest(course.marksCsv());

        FittedModel m = fit(in.design(), in.responses(), Settings.defaults().withDraws(200, 0));

        double[] truth = truthByLabel(course, in);
        for (int g = 0; g < truth.length; g++) {
            assertThat(m.severity(g)).isCloseTo(truth[g], within(1.5));
            double[] ci = m.interval95(g);
            assertThat(truth[g]).isBetween(ci[0] - 0.5, ci[1] + 0.5);
        }
    }

    @Test
    @DisplayName("identical assistants: the no-effect test does not cry wolf")
    void noEffectTestAcceptsIdenticalAssistants() {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.FIRST_YEAR_CORE, 2, 0.0);
        CsvIngest.IngestResult in = ingest(course.marksCsv());

        FittedModel m = fit(in.design(), in.responses(), Settings.defaults().withDraws(0, 49));

        assertThat(m.noEffect().pValue()).isGreaterThan(0.05);
    }

    @Test
    @DisplayName("tutorial groups, identical assistants: a lucky pile is not called a harsh assistant")
    void noEffectTestAllowsForTheLuckOfTheDeal() {
        // Regression, found by the benchmark: the no-effect worlds kept every student in their
        // real pile, so the chance that one tutor got the stronger group was never simulated
        // and was reported as severity, in 4 of 4 such courses.
        int falseAlarms = 0;
        for (long seed = 1; seed <= 4; seed++) {
            CourseSimulator.SimulatedCourse course =
                    CourseSimulator.simulate(CourseSimulator.Archetype.TUTORIAL_GROUPS, seed, 0.0);
            CsvIngest.IngestResult in = ingest(course.marksCsv());
            FittedModel m = fit(in.design(), in.responses(), Settings.defaults().withDraws(0, 49));
            if (m.noEffect().rejectsAt(0.05)) falseAlarms++;
        }
        assertThat(falseAlarms).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("clearly different assistants: the no-effect test rejects")
    void noEffectTestRejectsRealDifferences() {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.FIRST_YEAR_CORE, 2, 0.5);
        CsvIngest.IngestResult in = ingest(course.marksCsv());

        FittedModel m = fit(in.design(), in.responses(), Settings.defaults().withDraws(0, 49));

        assertThat(m.noEffect().pValue()).isLessThan(0.05);
    }

    @Test
    @DisplayName("the same seed gives the same report, however the threads are scheduled")
    void reproducible() {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.PG_ROTATED, 5);
        CsvIngest.IngestResult in = ingest(course.marksCsv());
        Settings s = Settings.defaults().withDraws(50, 19);

        FittedModel a = fit(in.design(), in.responses(), s);
        FittedModel b = fit(in.design(), in.responses(), s);

        assertThat(a.severityDraws()).isDeepEqualTo(b.severityDraws());
        assertThat(a.noEffect().pValue()).isEqualTo(b.noEffect().pValue());
    }

    @Test
    @DisplayName("shrinkage pulls a thinly-evidenced severity towards the panel, never past it")
    void shrinkageOnlyPullsTowardsZero() {
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(CourseSimulator.Archetype.CE_CORE, 4, 0.5);
        CsvIngest.IngestResult in = ingest(course.marksCsv());

        FittedModel loose = fit(in.design(), in.responses(), Settings.defaults().withShrinkage(0, 0).withDraws(0, 0));
        FittedModel tight = fit(in.design(), in.responses(), Settings.defaults().withShrinkage(0, 5000).withDraws(0, 0));

        double looseSpread = Arrays.stream(loose.graders().estimates()).map(Math::abs).sum();
        double tightSpread = Arrays.stream(tight.graders().estimates()).map(Math::abs).sum();
        assertThat(tightSpread).isLessThan(looseSpread);
    }

    // ------------------------------------------------------------------ helpers

    private static FittedModel fit(MarkingDesign design, List<Response> responses, Settings settings) {
        AuditVerdict verdict = IdentifiabilityAudit.audit(design);
        return SeverityEstimator.fit(AnchoredDesign.of(design, verdict, Anchoring.standard()), responses, settings);
    }

    private static CsvIngest.IngestResult ingest(String csv) {
        try {
            return CsvIngest.read(new BufferedReader(new StringReader(csv)), Pseudonymiser.withSalt(new byte[32]));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    /** True severities lined up with the pseudonymised assistants, centred like the estimates. */
    private static double[] truthByLabel(CourseSimulator.SimulatedCourse course, CsvIngest.IngestResult in) {
        Map<String, String> realByLabel = new HashMap<>();
        for (String line : in.keyFileContents().split("\n")) {
            String[] p = line.split(",");
            if (p.length == 3 && p[1].startsWith("Assistant ")) realByLabel.put(p[1], p[0]);
        }
        List<String> labels = in.design().graderLabels();
        double[] t = new double[labels.size()];
        for (int g = 0; g < t.length; g++) t[g] = course.trueSeverity().get(realByLabel.get(labels.get(g)));
        double bar = Arrays.stream(t).average().orElse(0);
        for (int g = 0; g < t.length; g++) t[g] -= bar;
        return t;
    }

    private static List<String> labels(String prefix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(prefix + i);
        return out;
    }
}
