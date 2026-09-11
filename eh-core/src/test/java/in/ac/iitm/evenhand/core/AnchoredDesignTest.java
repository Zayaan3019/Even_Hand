package in.ac.iitm.evenhand.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The contract between auditing a design and estimating from it.
 *
 * <p>The estimator accepts only an {@link AnchoredDesign}, and an
 * {@code AnchoredDesign} exists only where the audit said one could. These tests are
 * what stop that from being a comment: they check that a refusal cannot be walked
 * past, that a verdict cannot be applied to marking it was not computed for --- which
 * is the failure to expect, since the audit runs weeks before the marks arrive and the
 * allocation can change in between --- and that a certificate is checked for being
 * true of the marking, not merely for carrying the right fingerprint.
 */
class AnchoredDesignTest {

    /** The exposure is not what these tests are about; every verdict must still carry one. */
    private static final Exposure NO_EXPOSURE = new Exposure(Exposure.Status.NONE, 0.0, 1.0);

    private static MarkingDesign twoGraderDesign() {
        return MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0), new DesignCell(0, 0, 1),
                        new DesignCell(1, 0, 0), new DesignCell(1, 0, 1)),
                List.of("Student1", "Student2"), List.of("Q1"), List.of("Assistant 1", "Assistant 2"));
    }

    private static MarkingDesign otherDesign() {
        return MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0), new DesignCell(0, 1, 1)),
                List.of("Student1"), List.of("Q1", "Q2"), List.of("Assistant 1", "Assistant 2"));
    }

    /**
     * The true certificate for {@link #twoGraderDesign()}: five parameters, rank three,
     * and a null space made only of the two shifts every design has (abilities with
     * difficulties, abilities with severities).
     */
    private static IdentifiabilityCertificate cleanCertificateFor(MarkingDesign design) {
        return new IdentifiabilityCertificate(3, 5, 0, design.fingerprint(), List.of(
                new double[] {1, 1, 1, 0, 0},
                new double[] {1, 1, 0, 1, 1}));
    }

    /** Two assistants, each marking one question for both students: confounded. */
    private static MarkingDesign questionWiseDesign() {
        return MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0), new DesignCell(1, 0, 0),
                        new DesignCell(0, 1, 1), new DesignCell(1, 1, 1)),
                List.of("Student1", "Student2"), List.of("Q1", "Q2"), List.of("Assistant 1", "Assistant 2"));
    }

    @Test
    @DisplayName("a refused design cannot be estimated from, and the refusal survives the throw")
    void refusalCannotBeWalkedPast() {
        MarkingDesign design = twoGraderDesign();
        AuditVerdict.NotEstimable refusal = new AuditVerdict.NotEstimable(
                new IdentifiabilityCertificate(3, 5, 1, design.fingerprint(), List.of()),
                List.of(Diagnostic.error(Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                        "confounded", List.of("Assistant 1"), "second-mark Q1")),
                NO_EXPOSURE,
                "no two assistants share enough marking to be compared");

        // The point of the type. Downstream code cannot obtain the estimator's input at
        // all, so "the platform says so and stops" is not a branch anyone can forget.
        AnchoredDesign.NotEstimableException thrown = catchThrowableOfType(
                AnchoredDesign.NotEstimableException.class,
                () -> AnchoredDesign.of(design, refusal, Anchoring.standard()));

        assertThat(thrown).isNotNull();
        // The refusal is carried, not flattened into a message, so the caller can render
        // the diagnostics and the fix rather than only reporting that something failed.
        assertThat(thrown.verdict().reason()).contains("share enough marking");
        assertThat(thrown.verdict().diagnostics()).hasSize(1);
        assertThat(thrown.verdict().diagnostics().get(0).fixIt()).isPresent();
    }

    @Test
    @DisplayName("a certificate is evidence about one arrangement and cannot be moved to another")
    void certificateCannotBeTransplanted() {
        MarkingDesign audited = twoGraderDesign();
        MarkingDesign unaudited = otherDesign();
        AuditVerdict clean = new AuditVerdict.Estimable(cleanCertificateFor(audited), List.of(), NO_EXPOSURE);

        // Without this check the seam would accept a verdict earned on a well-linked
        // course as licence to estimate on a badly-linked one, which is the exact
        // mistake the audit exists to prevent.
        assertThatThrownBy(() -> AnchoredDesign.of(unaudited, clean, Anchoring.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different marking design");
    }

    @Test
    @DisplayName("a certificate claiming more identification than the marking supports is caught")
    void forgedFullIdentificationIsCaught() {
        MarkingDesign confounded = questionWiseDesign();
        // Internally consistent and carrying the right fingerprint, so only checking it
        // against the marking itself can expose it: the true rank is 3, not 4.
        IdentifiabilityCertificate forged = new IdentifiabilityCertificate(4, 6, 0, confounded.fingerprint(),
                List.of(new double[] {1, 1, 1, 1, 0, 0}, new double[] {1, 1, 0, 0, 1, 1}));

        assertThatThrownBy(() -> AnchoredDesign.of(confounded,
                new AuditVerdict.Estimable(forged, List.of(), NO_EXPOSURE), Anchoring.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only 3 can be confirmed");
    }

    @Test
    @DisplayName("a certificate claiming more confounding than the marking contains is caught")
    void forgedConfoundingIsCaught() {
        MarkingDesign design = twoGraderDesign();
        IdentifiabilityCertificate understated = new IdentifiabilityCertificate(2, 5, 1, design.fingerprint(),
                List.of(new double[] {1, 1, 1, 0, 0}, new double[] {1, 1, 0, 1, 1}, new double[] {0, 0, 0, 1, -1}));

        // Understating identification is the safe direction of error, but it is still an
        // error: it would tell the instructor a conclusion rests on an assumption when the
        // marking settles it outright.
        assertThatThrownBy(() -> AnchoredDesign.of(design,
                new AuditVerdict.EstimableUnderExchangeability(understated, List.of(), NO_EXPOSURE,
                        "piles alike", "interleaved"),
                Anchoring.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more confounding than the marking contains");
    }

    @Test
    @DisplayName("a null basis the marking does not annihilate is not a null basis")
    void nullBasisIsCheckedAgainstTheMarking() {
        MarkingDesign design = twoGraderDesign();
        IdentifiabilityCertificate wrongBasis = new IdentifiabilityCertificate(3, 5, 0, design.fingerprint(),
                List.of(new double[] {1, 1, 1, 0, 0}, new double[] {0, 0, 0, 1, -1}));

        assertThatThrownBy(() -> AnchoredDesign.of(design,
                new AuditVerdict.Estimable(wrongBasis, List.of(), NO_EXPOSURE), Anchoring.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a null direction");
    }

    @Test
    @DisplayName("a verdict cannot claim more than its certificate supports")
    void verdictCannotOverclaim() {
        MarkingDesign design = twoGraderDesign();

        // Estimable means "identified from the marking alone". Constructing one over a
        // certificate that still reports excess indeterminacy is a contradiction, and is
        // rejected where it is written rather than surfacing later as a wrong number.
        assertThatThrownBy(() -> new AuditVerdict.Estimable(
                new IdentifiabilityCertificate(3, 5, 2, design.fingerprint(), List.of()), List.of(), NO_EXPOSURE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excessNullity");
    }

    @Test
    @DisplayName("a verdict cannot claim less than its certificate supports either")
    void verdictCannotUnderclaim() {
        MarkingDesign design = twoGraderDesign();

        // The mirror image, and it matters for honesty rather than correctness: reporting
        // a result as resting on an assumption when the design identifies it outright
        // teaches the instructor to discount conclusions that deserve full weight.
        assertThatThrownBy(() -> AnchoredDesign.of(design,
                new AuditVerdict.EstimableUnderExchangeability(
                        cleanCertificateFor(design), List.of(), NO_EXPOSURE, "piles alike", "interleaved"),
                Anchoring.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claim less");
    }

    @Test
    @DisplayName("a clean verdict about its own design passes, and reports what it was anchored to")
    void cleanVerdictPasses() {
        MarkingDesign design = twoGraderDesign();
        AuditVerdict clean = new AuditVerdict.Estimable(cleanCertificateFor(design), List.of(), NO_EXPOSURE);

        AnchoredDesign anchored = AnchoredDesign.of(design, clean, Anchoring.standard());

        assertThat(anchored.design()).isSameAs(design);
        assertThat(anchored.verdict()).isSameAs(clean);
        // Anchoring is imposed deliberately and reported, never left as whatever the
        // optimiser happened to land on.
        assertThat(anchored.anchoring().kind())
                .isEqualTo(Anchoring.Kind.GRADERS_AND_QUESTIONS_CENTRED);
        assertThat(anchored.anchoring().description()).isNotBlank();
    }

    @Test
    @DisplayName("the design fingerprint ignores row order but notices a changed assignment")
    void fingerprintIsOrderIndependentButContentSensitive() {
        MarkingDesign a = MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0), new DesignCell(1, 0, 1)),
                List.of("Student1", "Student2"), List.of("Q1"), List.of("Assistant 1", "Assistant 2"));
        MarkingDesign reordered = MarkingDesign.of(
                List.of(new DesignCell(1, 0, 1), new DesignCell(0, 0, 0)),
                List.of("Student1", "Student2"), List.of("Q1"), List.of("Assistant 1", "Assistant 2"));
        MarkingDesign changed = MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0), new DesignCell(1, 0, 0)),
                List.of("Student1", "Student2"), List.of("Q1"), List.of("Assistant 1", "Assistant 2"));

        // A CSV sorted differently is the same marking arrangement and must not look like
        // a new one; a script handed to a different assistant is a different arrangement
        // and must.
        assertThat(reordered.fingerprint()).isEqualTo(a.fingerprint());
        assertThat(changed.fingerprint()).isNotEqualTo(a.fingerprint());
    }

    @Test
    @DisplayName("the fingerprint notices a changed marking order, and a change in which mark counts")
    void fingerprintCoversOrderAndMarkOfRecord() {
        List<String> students = List.of("Student1");
        List<String> questions = List.of("Q1");
        List<String> graders = List.of("Assistant 1", "Assistant 2");
        MarkingDesign base = MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0, 0, 1), new DesignCell(0, 0, 1, 0, 2)),
                students, questions, graders);
        MarkingDesign reorderedMarking = MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0, 0, 2), new DesignCell(0, 0, 1, 0, 1)),
                students, questions, graders);
        MarkingDesign secondMarkOnly = MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0, 0, 1), new DesignCell(0, 0, 1, 0, 2).asSecondMark()),
                students, questions, graders);

        // The drift audit reads the order and the exposure audit reads which mark counts,
        // so a verdict computed before either changed must not be taken as still current.
        assertThat(reorderedMarking.fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(secondMarkOnly.fingerprint()).isNotEqualTo(base.fingerprint());
    }

    @Test
    @DisplayName("the design drops every score, so the audit cannot read one")
    void designCarriesNoScores() {
        List<Response> responses = List.of(
                new Response(new DesignCell(0, 0, 0), 3, 5),
                new Response(new DesignCell(1, 0, 1), 5, 5));

        MarkingDesign design = MarkingDesign.fromResponses(responses,
                List.of("Student1", "Student2"), List.of("Q1"), List.of("Assistant 1", "Assistant 2"));

        // Structural, not incidental: MarkingDesign has no score-shaped field at all, so
        // "the audit runs before any mark is shared" is enforced by what the type can
        // hold rather than by our restraint in using it.
        assertThat(design.size()).isEqualTo(2);
        assertThat(design.cells()).containsExactly(new DesignCell(0, 0, 0), new DesignCell(1, 0, 1));
    }
}
