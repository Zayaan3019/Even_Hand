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
 * past, and that a verdict cannot be applied to marking it was not computed for ---
 * which is the failure to expect, since the audit runs weeks before the marks arrive
 * and the allocation can change in between.
 */
class AnchoredDesignTest {

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

    private static IdentifiabilityCertificate cleanCertificateFor(MarkingDesign design) {
        return new IdentifiabilityCertificate(4, 5, 0, design.fingerprint(), List.of());
    }

    @Test
    @DisplayName("a refused design cannot be estimated from, and the refusal survives the throw")
    void refusalCannotBeWalkedPast() {
        MarkingDesign design = twoGraderDesign();
        AuditVerdict.NotEstimable refusal = new AuditVerdict.NotEstimable(
                new IdentifiabilityCertificate(3, 5, 1, design.fingerprint(), List.of()),
                List.of(Diagnostic.error(Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                        "confounded", List.of("Assistant 1"), "second-mark Q1")),
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
        AuditVerdict clean = new AuditVerdict.Estimable(cleanCertificateFor(audited), List.of());

        // Without this check the seam would accept a verdict earned on a well-linked
        // course as licence to estimate on a badly-linked one, which is the exact
        // mistake the audit exists to prevent.
        assertThatThrownBy(() -> AnchoredDesign.of(unaudited, clean, Anchoring.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different marking design");
    }

    @Test
    @DisplayName("a verdict cannot claim more than its certificate supports")
    void verdictCannotOverclaim() {
        MarkingDesign design = twoGraderDesign();

        // Estimable means "identified from the marking alone". Constructing one over a
        // certificate that still reports excess indeterminacy is a contradiction, and is
        // rejected where it is written rather than surfacing later as a wrong number.
        assertThatThrownBy(() -> new AuditVerdict.Estimable(
                new IdentifiabilityCertificate(3, 5, 2, design.fingerprint(), List.of()), List.of()))
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
                        cleanCertificateFor(design), List.of(), "piles alike", "interleaved"),
                Anchoring.standard()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claim less");
    }

    @Test
    @DisplayName("a clean verdict about its own design passes, and reports what it was anchored to")
    void cleanVerdictPasses() {
        MarkingDesign design = twoGraderDesign();
        AuditVerdict clean = new AuditVerdict.Estimable(cleanCertificateFor(design), List.of());

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
