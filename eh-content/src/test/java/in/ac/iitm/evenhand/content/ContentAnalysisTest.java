package in.ac.iitm.evenhand.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The content analysis against courses whose truth is known. */
class ContentAnalysisTest {

    private static final ContentSettings FAST = ContentSettings.defaults().withDraws(100);

    private static double rmse(double[] est, double[] truth) {
        double ss = 0;
        for (int j = 0; j < est.length; j++) ss += Math.pow(est[j] - truth[j], 2);
        return Math.sqrt(ss / est.length);
    }

    @Test
    @DisplayName("random piles: recovers planted severities within 2 points, and calls them real")
    void randomPiles() {
        SyntheticCourse c = new SyntheticCourse(240, 4, new double[] {8, 0, -8}, SyntheticCourse.Allocation.RANDOM,
                0, 0, null, 11);
        ContentResult r = ContentAnalysis.analyse(c.input(), FAST);
        assertThat(r.status()).isEqualTo(ContentResult.Status.USABLE);
        assertThat(r.linksEveryAssistant()).isTrue();
        assertThat(rmse(r.severity(), c.trueSeverity)).isLessThan(2.0);
        assertThat(r.pValue()).isLessThan(0.05);
        assertThat(r.causal()).isPresent();
        assertThat(r.causal().get().placebo().pValue()).isLessThan(0.05);
    }

    @Test
    @DisplayName("piles dealt by ability: the raw gap is mostly the students, and the answers say so")
    void confoundedPiles() {
        // Assistant 1 gets the strongest third and is harsh; a pile-mean comparison would call them lenient.
        SyntheticCourse c = new SyntheticCourse(300, 4, new double[] {6, 0, -6}, SyntheticCourse.Allocation.BY_ABILITY,
                0, 0, null, 12);
        ContentResult r = ContentAnalysis.analyse(c.input(), FAST);
        assertThat(r.status()).isEqualTo(ContentResult.Status.USABLE);
        assertThat(r.severity()[0]).isGreaterThan(2.0);
        assertThat(r.severity()[2]).isLessThan(-2.0);
        CausalFindings.Decomposition first = r.causal().orElseThrow().decomposition().get(0);
        assertThat(first.rawGap()).as("strong pile scores higher despite a harsh TA").isPositive();
        assertThat(first.contentGap()).as("and the answers explain that").isGreaterThan(first.rawGap());
        assertThat(r.causal().get().overlap().get(0).balanceBefore()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("identical assistants: no difference is claimed, by the bootstrap or by the placebo")
    void identicalAssistants() {
        SyntheticCourse c = new SyntheticCourse(240, 4, new double[] {0, 0, 0, 0}, SyntheticCourse.Allocation.RANDOM,
                0, 0, null, 13);
        ContentResult r = ContentAnalysis.analyse(c.input(), FAST);
        assertThat(r.pValue()).isGreaterThan(0.05);
        assertThat(Arrays.stream(r.severity()).map(Math::abs).max().orElse(0)).isLessThan(3.0);
        assertThat(r.causal().orElseThrow().mechanisms())
                .allMatch(m -> m.kind() == CausalFindings.Kind.NO_CLEAR_DIFFERENCE
                        || m.kind() == CausalFindings.Kind.ISOLATED_SLIPS);
    }

    @Test
    @DisplayName("question-wise marking: answers to a question were all marked by one TA, so nothing links them")
    void questionWise() {
        SyntheticCourse c = new SyntheticCourse(120, 4, new double[] {5, -5}, SyntheticCourse.Allocation.QUESTION_WISE,
                0, 0, null, 14);
        ContentResult r = ContentAnalysis.analyse(c.input(), FAST);
        assertThat(r.status()).isEqualTo(ContentResult.Status.NO_LINKS);
        assertThat(r.causal()).isEmpty();
    }

    @Test
    @DisplayName("double-marked scripts give an independent estimate that agrees with the matched one")
    void doubleMarked() {
        SyntheticCourse c = new SyntheticCourse(240, 4, new double[] {8, 0, -8}, SyntheticCourse.Allocation.RANDOM,
                0.25, 0, null, 15);
        CausalFindings.DoubleMarked dm = ContentAnalysis.analyse(c.input(), FAST).causal().orElseThrow()
                .doubleMarked().orElseThrow();
        assertThat(dm.scripts()).isGreaterThan(100);
        assertThat(dm.severity()[0]).isCloseTo(8, within(3.0));
        assertThat(dm.severity()[2]).isCloseTo(-8, within(3.0));
        assertThat(dm.agreement()).isGreaterThan(0.9);
    }

    @Test
    @DisplayName("planted slips are found as marks out of line with similar answers")
    void slips() {
        SyntheticCourse c = new SyntheticCourse(240, 4, new double[] {4, 0, -4}, SyntheticCourse.Allocation.RANDOM,
                0, 12, null, 16);
        ContentResult r = ContentAnalysis.analyse(c.input(), FAST);
        Set<String> flagged = new HashSet<>();
        for (ContentResult.OutOfLine o : r.outOfLine()) flagged.add(o.student() + "|" + o.question());
        int found = 0;
        for (int idx : c.slips) {
            var resp = c.responses.get(idx);
            String key = c.design.studentLabels().get(resp.student()) + "|" + c.design.questionLabels().get(resp.question());
            if (flagged.contains(key)) found++;
        }
        assertThat(found).as("slips found of %d", c.slips.size()).isGreaterThanOrEqualTo(c.slips.size() * 2 / 3);
        assertThat(r.outOfLine().size()).isLessThanOrEqualTo(c.slips.size() + 5);
    }

    @Test
    @DisplayName("a TA harsh on one question only is read as a rubric difference, not general severity")
    void questionSpecific() {
        double[][] extra = new double[3][4];
        extra[0][2] = 25;   // Assistant 1 takes 2.5 marks extra on Q3 only
        SyntheticCourse c = new SyntheticCourse(300, 4, new double[] {0, 0, 0}, SyntheticCourse.Allocation.RANDOM,
                0, 0, extra, 17);
        CausalFindings f = ContentAnalysis.analyse(c.input(), FAST).causal().orElseThrow();
        CausalFindings.Mechanism m = f.mechanisms().get(0);
        assertThat(m.kind()).isEqualTo(CausalFindings.Kind.QUESTION_SPECIFIC);
        assertThat(m.explanation()).contains("Q3");
    }

    @Test
    @DisplayName("Rosenbaum: a strong, consistent effect survives hidden bias; none survives at gamma 1")
    void sensitivity() {
        assertThat(ContentAnalysis.rosenbaumGamma(90, 100)).isGreaterThan(3);
        assertThat(ContentAnalysis.rosenbaumGamma(52, 100)).isEqualTo(1.0);
        SyntheticCourse c = new SyntheticCourse(240, 4, new double[] {10, 0, -10}, SyntheticCourse.Allocation.RANDOM,
                0, 0, null, 18);
        CausalFindings f = ContentAnalysis.analyse(c.input(), FAST).causal().orElseThrow();
        CausalFindings.Sensitivity harsh = f.sensitivity().get(0);
        assertThat(harsh.direction()).isEqualTo(1);
        assertThat(harsh.gamma()).isGreaterThan(1.5);
    }
}
