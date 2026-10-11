package in.ac.iitm.evenhand.outcome;

import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.LetterGrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static in.ac.iitm.evenhand.core.LetterGrade.A;
import static in.ac.iitm.evenhand.core.LetterGrade.B;
import static in.ac.iitm.evenhand.core.LetterGrade.C;
import static in.ac.iitm.evenhand.core.LetterGrade.D;
import static in.ac.iitm.evenhand.core.LetterGrade.E;
import static in.ac.iitm.evenhand.core.LetterGrade.S;
import static in.ac.iitm.evenhand.core.LetterGrade.U;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradingRuleTest {

    /** Ten totals with mean 50 and population SD 20, worked out by hand. */
    private static final double[] COHORT = { 90, 70, 50, 50, 50, 50, 50, 50, 30, 10 };

    @Test
    @DisplayName("sigma bands, worked by hand: B at the mean, A one SD up, C one down, D two down")
    void sigmaBands() {
        CutoffPolicy sigmaOnly = new CutoffPolicy(CutoffPolicy.Kind.SIGMA_BANDS, B,
                CutoffPolicy.iitmConventionalRelative().bandOffsets(), 0, Map.of(), CutoffPolicy.SBandPolicy.SIGMA_BINDS);

        LetterGrade[] g = GradingRule.grade(sigmaOnly, COHORT);

        // Edges: S 90, A 70, B 50, C 30, D 10, E -10.
        assertThat(g).containsExactly(S, A, B, B, B, B, B, B, C, D);
    }

    @Test
    @DisplayName("IITM convention: the top 10% get S whatever the sigma line says")
    void topTenPercentGetS() {
        double[] totals = { 99, 98, 97, 96, 95, 94, 93, 92, 91, 90, 50, 50, 50, 50, 50, 50, 50, 50, 50, 50 };

        LetterGrade[] g = GradingRule.grade(CutoffPolicy.iitmConventionalRelative(), totals);

        // 10% of 20 is two scripts, even though nobody is two SD above the mean.
        assertThat(g[0]).isEqualTo(S);
        assertThat(g[1]).isEqualTo(S);
        assertThat(g[2]).isNotEqualTo(S);
        assertThat(java.util.Arrays.stream(g).filter(x -> x == S).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("students tied at the top-share cut are all admitted, not split by file order")
    void tiesAtTheCutAreAllAdmitted() {
        double[] totals = { 80, 80, 80, 40, 40, 40, 40, 40, 40, 40 };

        LetterGrade[] g = GradingRule.grade(CutoffPolicy.iitmConventionalRelative(), totals);

        assertThat(g[0]).isEqualTo(S);
        assertThat(g[1]).isEqualTo(S);
        assertThat(g[2]).isEqualTo(S);
    }

    @Test
    @DisplayName("absolute thresholds ignore the cohort")
    void absolute() {
        CutoffPolicy rule = CutoffPolicy.absolute(Map.of(S, 90.0, A, 80.0, B, 70.0, C, 60.0, D, 50.0, E, 40.0));

        LetterGrade[] g = GradingRule.grade(rule, new double[] { 95, 85, 75, 65, 55, 45, 35, 90 });

        assertThat(g).containsExactly(S, A, B, C, D, E, U, S);
    }

    @Test
    @DisplayName("the boundaries move when the totals move: correcting marks moves the mean and SD")
    void boundariesAreRecomputed() {
        Map<LetterGrade, Double> before = GradingRule.boundaries(CutoffPolicy.iitmConventionalRelative(), COHORT);
        double[] shifted = COHORT.clone();
        for (int i = 0; i < shifted.length; i++) shifted[i] += 5;
        Map<LetterGrade, Double> after = GradingRule.boundaries(CutoffPolicy.iitmConventionalRelative(), shifted);

        assertThat(after.get(B)).isEqualTo(before.get(B) + 5);
        assertThat(after.get(C)).isEqualTo(before.get(C) + 5);
    }

    @Test
    @DisplayName("hand-drawn bands are refused: they have no counterfactual")
    void instructorBandsRefused() {
        CutoffPolicy hand = new CutoffPolicy(CutoffPolicy.Kind.INSTRUCTOR_BANDS, null, Map.of(), 0, Map.of(),
                CutoffPolicy.SBandPolicy.SIGMA_BINDS);

        assertThatThrownBy(() -> GradingRule.grade(hand, COHORT)).isInstanceOf(UnsupportedOperationException.class);
    }
}
