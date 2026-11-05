package in.ac.iitm.evenhand.content;

import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Exposure;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.IdentifiabilityCertificate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Which estimate corrects the grades: the marks, the marks less what the piles explain, or the answers. */
class ContentFitTest {

    private static final SyntheticCourse COURSE = new SyntheticCourse(240, 4, new double[] {8, 0, -8},
            SyntheticCourse.Allocation.RANDOM, 0, 0, null, 21);
    private static final ContentResult CONTENT = ContentAnalysis.analyse(COURSE.input(),
            ContentSettings.defaults().withDraws(100));

    private static final Exposure EXPOSED = new Exposure(Exposure.Status.UNDER_EXCHANGEABILITY, 10, 40);

    private static AuditVerdict underExchangeability() {
        return new AuditVerdict.EstimableUnderExchangeability(new IdentifiabilityCertificate(10, 12, 1, 0L, List.of()),
                List.of(), EXPOSED, "piles alike", "dealt at random");
    }

    /** A marks-only fit whose severities are the answers' own, shifted by {@code shift} per TA. */
    private static FittedModel marksFit(double[] shift) {
        double[] sev = CONTENT.severity().clone();
        double[][] draws = new double[CONTENT.draws().length][];
        for (int b = 0; b < draws.length; b++) {
            draws[b] = CONTENT.draws()[b].clone();
            for (int j = 0; j < sev.length; j++) draws[b][j] += shift[j];
        }
        for (int j = 0; j < sev.length; j++) sev[j] += shift[j];
        ContentResult shifted = new ContentResult(CONTENT.status(), CONTENT.embedder(), CONTENT.semantic(),
                CONTENT.textCoverage(), CONTENT.questions(), CONTENT.graderLabels(), sev, CONTENT.standardError(),
                CONTENT.samplingError(),
                CONTENT.pairCount(), CONTENT.groups(), draws, CONTENT.pValue(), CONTENT.observedSpread(), CONTENT.signal(),
                CONTENT.matchedShare(), List.of(), List.of(), List.of(), Optional.empty(), List.of(), CONTENT.balance());
        return ContentFit.toFittedModel(shifted, underExchangeability(), COURSE.design, COURSE.responses);
    }

    @Test
    @DisplayName("when the answers show no gap between the piles, the marks estimate is used and nothing is called proved")
    void balancedPilesKeepTheMarks() {
        ContentFit.Choice c = ContentFit.choose(underExchangeability(), Optional.of(marksFit(new double[3])), CONTENT);
        assertThat(c.useAnswers()).isFalse();
        assertThat(c.adjustForPiles()).isFalse();
        assertThat(c.pValue()).isGreaterThanOrEqualTo(ContentFit.PILE_ALPHA);
        assertThat(c.explanation()).contains("does not prove");
        assertThat(ContentFit.licence(underExchangeability(), Optional.of(marksFit(new double[3])), CONTENT)).isEmpty();
    }

    /** One pile looks stronger from its answers: the same numbers as {@link #CONTENT}, with a known gap. */
    private static ContentResult withGap(double[] gap, double scriptSignal) {
        double[][] draws = new double[200][];
        for (int b = 0; b < draws.length; b++) {
            draws[b] = gap.clone();
            for (int j = 0; j < gap.length; j++) draws[b][j] += 0.2 * Math.sin(b * (j + 1.0));
        }
        return new ContentResult(CONTENT.status(), CONTENT.embedder(), CONTENT.semantic(), CONTENT.textCoverage(),
                CONTENT.questions(), CONTENT.graderLabels(), CONTENT.severity(), CONTENT.standardError(), CONTENT.samplingError(),
                CONTENT.pairCount(), CONTENT.groups(), CONTENT.draws(), CONTENT.pValue(), CONTENT.observedSpread(), CONTENT.signal(),
                CONTENT.matchedShare(), List.of(), List.of(), List.of(), Optional.empty(), List.of(),
                new PileBalance(gap, scriptSignal, draws));
    }

    @Test
    @DisplayName("when the piles differ, the gap the piles explain is taken out, and a gap they fully explain is no finding")
    void unequalPilesAreAdjustedFor() {
        ContentResult unequal = withGap(new double[] {3, 0, -3}, 0.5);
        // The marks flatter the stronger pile by exactly what its answers imply: gap / R^2 = 6 points.
        FittedModel flattered = marksFit(new double[3]);
        double[] shift = {-6 - flattered.severity(0), -flattered.severity(1), 6 - flattered.severity(2)};
        Optional<FittedModel> marks = Optional.of(marksFit(shift));

        ContentFit.Choice c = ContentFit.choose(underExchangeability(), marks, unequal);
        assertThat(c.adjustForPiles()).isTrue();
        assertThat(c.useAnswers()).isFalse();
        assertThat(c.explanation()).contains("not alike", "assumption");
        assertThat(ContentFit.licence(underExchangeability(), marks, unequal)).isEmpty();

        FittedModel adjusted = ContentFit.adjustedForPiles(marks.get(), unequal, 0.15, 1.5);
        for (int j = 0; j < 3; j++) assertThat(adjusted.severity(j)).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(adjusted.noEffect().rejectsAt(0.05)).isFalse();
    }

    /** A pile gap of {1, 0, -1} whose draws reach it {@code beyond} times in 200: p = (1 + beyond) / 201. */
    private static ContentResult closeTo(int beyond) {
        double[] gap = {1, 0, -1};
        double[][] draws = new double[200][];
        for (int b = 0; b < draws.length; b++) {
            draws[b] = gap.clone();
            if (b < beyond) for (int j = 0; j < 3; j++) draws[b][j] += 1.5 * gap[j];
        }
        return new ContentResult(CONTENT.status(), CONTENT.embedder(), CONTENT.semantic(), CONTENT.textCoverage(),
                CONTENT.questions(), CONTENT.graderLabels(), CONTENT.severity(), CONTENT.standardError(), CONTENT.samplingError(),
                CONTENT.pairCount(), CONTENT.groups(), CONTENT.draws(), CONTENT.pValue(), CONTENT.observedSpread(), CONTENT.signal(),
                CONTENT.matchedShare(), List.of(), List.of(), List.of(), Optional.empty(), List.of(),
                new PileBalance(gap, 0.5, draws));
    }

    @Test
    @DisplayName("a close call on either side of the 10% line is called one; a clear result is not")
    void closeCallsAreSaid() {
        Optional<FittedModel> marks = Optional.of(marksFit(new double[3]));
        ContentFit.Choice justAdjusted = ContentFit.choose(underExchangeability(), marks, closeTo(15));   // p = 0.080
        ContentFit.Choice justKept = ContentFit.choose(underExchangeability(), marks, closeTo(30));       // p = 0.154
        ContentFit.Choice clear = ContentFit.choose(underExchangeability(), marks, closeTo(0));           // p = 0.005
        ContentFit.Choice farOff = ContentFit.choose(underExchangeability(), marks, closeTo(100));        // p = 0.502

        assertThat(justAdjusted.adjustForPiles()).isTrue();
        assertThat(justAdjusted.explanation()).contains("close call");
        assertThat(justKept.adjustForPiles()).isFalse();
        assertThat(justKept.explanation()).contains("close call");
        assertThat(clear.adjustForPiles()).isTrue();
        assertThat(clear.explanation()).doesNotContain("close call");
        assertThat(farOff.adjustForPiles()).isFalse();
        assertThat(farOff.explanation()).doesNotContain("close call");
    }

    @Test
    @DisplayName("a real gap larger than the piles explain survives the adjustment")
    void aGapBeyondThePilesSurvives() {
        ContentResult unequal = withGap(new double[] {3, 0, -3}, 0.5);
        FittedModel base = marksFit(new double[3]);
        double[] shift = {-26 - base.severity(0), -base.severity(1), 26 - base.severity(2)};
        FittedModel adjusted = ContentFit.adjustedForPiles(marksFit(shift), unequal, 0.15, 1.5);
        assertThat(adjusted.severity(0)).isCloseTo(-20, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(adjusted.noEffect().rejectsAt(0.05)).isTrue();
    }

    @Test
    @DisplayName("answers that say almost nothing cannot blow the adjustment up: the floor holds it")
    void theFloorHoldsWhenTheAnswersSayLittle() {
        ContentResult unequal = withGap(new double[] {3, 0, -3}, 0.01);
        FittedModel base = marksFit(new double[3]);
        FittedModel adjusted = ContentFit.adjustedForPiles(base, unequal, 0.15, 1.5);
        assertThat(adjusted.severity(0) - base.severity(0)).isCloseTo(20, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("an assumption-free marks estimate is never replaced, and no estimate at all defers to the answers")
    void edgeCases() {
        AuditVerdict estimable = new AuditVerdict.Estimable(new IdentifiabilityCertificate(10, 12, 0, 0L, List.of()),
                List.of(), EXPOSED);
        assertThat(ContentFit.choose(estimable, Optional.of(marksFit(new double[] {-10, 0, 10})), CONTENT).useAnswers())
                .isFalse();
        assertThat(ContentFit.choose(underExchangeability(), Optional.empty(), CONTENT).useAnswers()).isTrue();
    }
}
