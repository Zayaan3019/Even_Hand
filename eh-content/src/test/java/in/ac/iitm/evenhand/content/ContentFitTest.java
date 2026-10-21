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

/** Which estimate corrects the grades: the Hausman choice between marks-only and answer-anchored. */
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
                CONTENT.matchedShare(), List.of(), List.of(), List.of(), Optional.empty(), List.of());
        return ContentFit.toFittedModel(shifted, underExchangeability(), COURSE.design, COURSE.responses);
    }

    @Test
    @DisplayName("when the two estimates agree, the answers confirm the piles were alike: the marks estimate is used")
    void agreementKeepsTheMarks() {
        ContentFit.Choice c = ContentFit.choose(underExchangeability(), Optional.of(marksFit(new double[3])), CONTENT);
        assertThat(c.useAnswers()).isFalse();
        assertThat(c.pValue()).isGreaterThan(0.5);
        assertThat(c.explanation()).contains("confirm");
        assertThat(ContentFit.licence(underExchangeability(), Optional.of(marksFit(new double[3])), CONTENT)).isEmpty();
    }

    @Test
    @DisplayName("when they disagree, the piles were not alike: the answers' estimate is used and the gap is named")
    void disagreementUsesTheAnswers() {
        Optional<FittedModel> biased = Optional.of(marksFit(new double[] {-10, 0, 10}));
        ContentFit.Choice c = ContentFit.choose(underExchangeability(), biased, CONTENT);
        assertThat(c.useAnswers()).isTrue();
        assertThat(c.pValue()).isLessThan(0.05);
        assertThat(c.explanation()).contains("not", "10.0 points");
        assertThat(ContentFit.licence(underExchangeability(), biased, CONTENT)).get()
                .satisfies(v -> assertThat(v.exposure().status()).isEqualTo(Exposure.Status.ESTIMABLE));
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
