package in.ac.iitm.evenhand.outcome;

import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Facet;
import in.ac.iitm.evenhand.core.FacetEstimates;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;
import in.ac.iitm.evenhand.design.IdentifiabilityAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate in front of the re-check list, on hand-built fits so each case isolates one rule.
 * Forty students; each of two questions out of 10 is marked by both assistants, assistant 1's
 * mark counting for the first twenty students and assistant 2's for the rest, so the two
 * halves of the class were exposed to different assistants and the marking links them.
 */
class OutcomeTest {

    private static final CutoffPolicy ABSOLUTE = CutoffPolicy.absolute(Map.of(
            LetterGrade.S, 90.0, LetterGrade.A, 80.0, LetterGrade.B, 70.0,
            LetterGrade.C, 60.0, LetterGrade.D, 50.0, LetterGrade.E, 40.0));

    private final List<Response> responses = new ArrayList<>();
    private final MarkingDesign design;
    private final AuditVerdict verdict;

    OutcomeTest() {
        List<String> students = new ArrayList<>();
        for (int s = 0; s < 40; s++) {
            students.add("Student " + (s + 1));
            int recordBy = s < 20 ? 0 : 1;
            int score = 3 + (s % 7);   // totals 30..90, many sitting on a ten-mark boundary
            for (int q = 0; q < 2; q++) {
                for (int g = 0; g < 2; g++) {
                    responses.add(new Response(new DesignCell(s, q, g, 0, DesignCell.NO_POSITION, g == recordBy),
                            score, 10));
                }
            }
        }
        design = MarkingDesign.fromResponses(responses, students, List.of("Q1", "Q2"),
                List.of("Assistant 1", "Assistant 2"));
        verdict = IdentifiabilityAudit.audit(design);
    }

    /** A fit with fixed severities, every bootstrap draw equal to them, and the given p-value. */
    private FittedModel fit(double[] severity, double pValue) {
        double[][] draws = new double[50][];
        for (int b = 0; b < draws.length; b++) draws[b] = severity.clone();
        FacetEstimates graders = new FacetEstimates(Facet.GRADER, design.graderLabels(), severity,
                new double[2], new int[] { 80, 80 }, new boolean[2]);
        FacetEstimates none = new FacetEstimates(Facet.STUDENT, List.of(), new double[0], new double[0],
                new int[0], new boolean[0]);
        return new FittedModel(verdict, Anchoring.standard(), 60, none, none, graders, List.of(List.of(0, 1)),
                10, draws, new FittedModel.NoEffectTest(Math.abs(severity[0]), pValue, 99),
                new FittedModel.FitDiagnostics(10, 0, true));
    }

    @Test
    @DisplayName("a lenient assistant: the students they lifted over a boundary are listed, with certainty")
    void emitsTheStudentsALenientAssistantMoved() {
        // Assistant 2 is 5 points lenient: half a mark on each 10-mark question, 5 on the total.
        // Totals sit exactly on the ten-mark boundaries, so correcting assistant 1's students up
        // by 5 crosses none, while correcting assistant 2's down by 5 drops each a grade.
        Outcome.Result r = Outcome.assess(fit(new double[] { 5, -5 }, 0.01), responses, design,
                new double[] { 1 }, ABSOLUTE, Outcome.Gate.standard());

        assertThat(r.shortlist()).isInstanceOf(Outcome.Emitted.class);
        Outcome.Emitted e = (Outcome.Emitted) r.shortlist();
        assertThat(e.rows()).isNotEmpty().hasSizeLessThanOrEqualTo(20);
        assertThat(e.rows()).allSatisfy(row -> assertThat(row.probability()).isEqualTo(1.0));
        assertThat(e.expectedFalseFlags()).isZero();
        for (Outcome.Row row : e.rows()) {
            int i = design.studentLabels().indexOf(row.student());
            assertThat(i).isGreaterThanOrEqualTo(20);
            assertThat(row.expectedPoints()).isNegative();
        }
        assertThat(r.changed()).isEqualTo(r.changedLow()).isEqualTo(r.changedHigh());
        assertThat(r.assistantShare()).isPositive();
    }

    @Test
    @DisplayName("differences within chance: no list, and the reason says so")
    void refusesWhenTheNoEffectTestDoesNot() {
        Outcome.Result r = Outcome.assess(fit(new double[] { 5, -5 }, 0.30), responses, design,
                new double[] { 1 }, ABSOLUTE, Outcome.Gate.standard());

        assertThat(r.shortlist()).isInstanceOfSatisfying(Outcome.Refused.class,
                ref -> assertThat(ref.reason()).contains("chance"));
    }

    @Test
    @DisplayName("no uncertainty computed: no list, rather than one with made-up confidence")
    void refusesWithoutDraws() {
        FittedModel noDraws = fit(new double[] { 5, -5 }, 0.01);
        noDraws = new FittedModel(noDraws.verdict(), noDraws.anchoring(), noDraws.mean(), noDraws.students(),
                noDraws.questions(), noDraws.graders(), noDraws.groups(), noDraws.residualSd(), new double[0][],
                noDraws.noEffect(), noDraws.diagnostics());

        Outcome.Result r = Outcome.assess(noDraws, responses, design, new double[] { 1 }, ABSOLUTE,
                Outcome.Gate.standard());

        assertThat(r.shortlist()).isInstanceOf(Outcome.Refused.class);
    }

    @Test
    @DisplayName("the list is capped at the gate's length, most consequential first")
    void cappedAndRanked() {
        Outcome.Gate tight = new Outcome.Gate(0.05, 0.2, 3);

        Outcome.Result r = Outcome.assess(fit(new double[] { 15, -15 }, 0.01), responses, design,
                new double[] { 1 }, ABSOLUTE, tight);

        Outcome.Emitted e = (Outcome.Emitted) r.shortlist();
        assertThat(e.rows()).hasSize(3);
        for (int k = 1; k < e.rows().size(); k++) {
            assertThat(Math.abs(e.rows().get(k - 1).expectedPoints()))
                    .isGreaterThanOrEqualTo(Math.abs(e.rows().get(k).expectedPoints()));
        }
    }

    @Test
    @DisplayName("the share of the spread due to the assistants, worked by hand: 6.48%")
    void assistantShareByHand() {
        // Totals are 10 x (3 + s mod 7) for s = 0..39: residues 0-4 six times, 5-6 five times, so
        // the variance of the totals is 100 x 3.859375 = 385.9375. Correction moves the first
        // half by +5 and the second by -5 (nobody hits 0 or full marks), so the variance of the
        // difference is 25. Share = 100 x 25 / 385.9375.
        Outcome.Result r = Outcome.assess(fit(new double[] { 5, -5 }, 0.01), responses, design,
                new double[] { 1 }, ABSOLUTE, Outcome.Gate.standard());

        assertThat(r.assistantShare()).isCloseTo(100.0 * 25 / 385.9375, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("uncertain draws: probabilities are fractions, the threshold is respected, the interval brackets the estimate")
    void uncertainDraws() {
        // Half the draws say the assistants are identical, half say they differ by 10 points.
        FittedModel base = fit(new double[] { 5, -5 }, 0.01);
        double[][] draws = new double[40][];
        for (int b = 0; b < draws.length; b++) draws[b] = b % 2 == 0 ? new double[] { 0, 0 } : new double[] { 5, -5 };
        FittedModel m = new FittedModel(base.verdict(), base.anchoring(), base.mean(), base.students(),
                base.questions(), base.graders(), base.groups(), base.residualSd(), draws, base.noEffect(),
                base.diagnostics());

        Outcome.Result r = Outcome.assess(m, responses, design, new double[] { 1 }, ABSOLUTE, new Outcome.Gate(0.05, 0.5, 20));

        assertThat(r.changedLow()).isZero();
        assertThat(r.changedHigh()).isEqualTo(r.changed());
        Outcome.Emitted e = (Outcome.Emitted) r.shortlist();
        assertThat(e.rows()).isNotEmpty().allSatisfy(row -> {
            assertThat(row.probability()).isEqualTo(0.5);               // exactly at the threshold: kept
            assertThat(row.likelyGrade()).isNotEqualTo(row.gradeAsMarked());
        });
        assertThat(e.expectedFalseFlags()).isEqualTo(0.5 * e.rows().size());
    }

    @Test
    @DisplayName("identical assistants change nobody's grade")
    void zeroSeverityChangesNothing() {
        Outcome.Result r = Outcome.assess(fit(new double[] { 0, 0 }, 0.01), responses, design,
                new double[] { 1 }, ABSOLUTE, Outcome.Gate.standard());

        assertThat(r.changed()).isZero();
        assertThat(r.gradesCorrected()).isEqualTo(r.gradesAsMarked());
    }
}
