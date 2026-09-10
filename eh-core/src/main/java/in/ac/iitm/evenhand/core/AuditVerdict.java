package in.ac.iitm.evenhand.core;

import java.util.List;

/**
 * What the data can and cannot tell you. The platform's first output is not an
 * estimate; it is this.
 *
 * <p>The interface is sealed, so every consumer must handle every case, including the
 * refusals. That is the point: "where nothing is estimable, the platform says so and
 * stops" is a property the compiler checks, not a convention someone might forget.
 *
 * <h2>Why there are four cases and not two</h2>
 *
 * <p>Computing the rank of the design matrix rather than inspecting a connectivity
 * graph turns up a distinction that a yes/no verdict would hide, and that matters to
 * the two arrangements IIT Madras courses actually use.
 *
 * <p>Under <strong>question-wise</strong> marking, an assistant's severity is only
 * ever observed added to the difficulty of their one question. Nothing rescues that:
 * no assumption about students separates the two, because no student was ever marked
 * on that question by anyone else. It is {@link NotEstimable}.
 *
 * <p>Under <strong>script-wise</strong> marking, where each assistant marks a whole
 * pile of scripts and nobody else touches them, an assistant's severity is instead
 * absorbed into the abilities of the students in their pile. As a matter of pure
 * algebra that is equally fatal &mdash; the rank deficiency is the same size. But it
 * is rescuable, by assuming the piles were comparable in ability, which is exactly
 * what random allocation is for. That is {@link EstimableUnderExchangeability}, and
 * it is reported as resting on an assumption because it does.
 *
 * <p>Collapsing those two into one verdict would either refuse a workable course or,
 * worse, quietly report severities that rest on an assumption nobody was told about.
 * The distinction is also why the audit alone cannot tell a random split from a fixed
 * roll-number split: the two produce <em>identical</em> designs, of identical rank.
 * Only the profiler's test of whether allocation looks random speaks to that, and it
 * is a claim about ability, not about identifiability.
 */
public sealed interface AuditVerdict
        permits AuditVerdict.Estimable,
                AuditVerdict.EstimableUnderExchangeability,
                AuditVerdict.PartiallyEstimable,
                AuditVerdict.NotEstimable {

    IdentifiabilityCertificate certificate();

    List<Diagnostic> diagnostics();

    /** A one-line summary in the instructor's terms, for the top of the report. */
    String headline();

    /** Whether the estimator may run at all on this design. */
    default boolean permitsEstimation() {
        return !(this instanceof NotEstimable);
    }

    /**
     * Every severity difference is estimable from the marking itself, with no
     * assumption about who was given which scripts.
     *
     * <p>Reached when some unit was marked by more than one assistant, or when the
     * arrangement crosses assistants against both students and questions &mdash; for
     * instance question-wise marking in which the assistant assigned to each question
     * rotates across blocks of students, which costs nothing and identifies
     * everything.
     */
    record Estimable(IdentifiabilityCertificate certificate,
                     List<Diagnostic> diagnostics) implements AuditVerdict {

        public Estimable {
            if (!certificate.isFullyIdentified()) {
                throw new IllegalArgumentException(
                        "Estimable requires excessNullity == 0, got " + certificate.excessNullity());
            }
            diagnostics = List.copyOf(diagnostics);
        }

        @Override
        public String headline() {
            return "Every assistant can be placed on one scale, from the marking alone.";
        }
    }

    /**
     * Severities are estimable only if the students each assistant was given are
     * assumed comparable in ability to those the others were given.
     *
     * <p>The usual script-wise case. The estimate is real and worth having, and the
     * assumption is the one the instructor made implicitly when they dealt the piles;
     * the platform's job is to say out loud that the conclusion rests on it, and to
     * report what the allocation looks like so the reader can judge.
     *
     * @param assumption the assumption in the instructor's words
     * @param allocationEvidence what the profiler saw about how piles were formed
     */
    record EstimableUnderExchangeability(IdentifiabilityCertificate certificate,
                                         List<Diagnostic> diagnostics,
                                         String assumption,
                                         String allocationEvidence) implements AuditVerdict {

        public EstimableUnderExchangeability {
            diagnostics = List.copyOf(diagnostics);
        }

        @Override
        public String headline() {
            return "Assistants can be compared, but only on the assumption that their piles of "
                    + "scripts were alike in ability. " + allocationEvidence;
        }
    }

    /**
     * Some assistants are comparable and some are not. Estimation proceeds for the
     * comparable set only, and the report names the others and why.
     *
     * @param estimableGraders indices of graders whose severity is estimable
     * @param blockedGraders   indices of graders whose severity is not
     */
    record PartiallyEstimable(IdentifiabilityCertificate certificate,
                              List<Diagnostic> diagnostics,
                              List<Integer> estimableGraders,
                              List<Integer> blockedGraders) implements AuditVerdict {

        public PartiallyEstimable {
            diagnostics = List.copyOf(diagnostics);
            estimableGraders = List.copyOf(estimableGraders);
            blockedGraders = List.copyOf(blockedGraders);
            if (estimableGraders.size() < 2) {
                throw new IllegalArgumentException(
                        "use NotEstimable when fewer than two graders are mutually comparable");
            }
        }

        @Override
        public String headline() {
            return estimableGraders.size() + " of " + (estimableGraders.size() + blockedGraders.size())
                    + " assistants can be placed on one scale; the rest cannot, and the report says why.";
        }
    }

    /**
     * Nothing about grader severity is estimable from this arrangement. The audit
     * reports what would repair it and the pipeline stops.
     */
    record NotEstimable(IdentifiabilityCertificate certificate,
                        List<Diagnostic> diagnostics,
                        String reason) implements AuditVerdict {

        public NotEstimable {
            diagnostics = List.copyOf(diagnostics);
        }

        @Override
        public String headline() {
            return "This marking arrangement cannot support a severity estimate: " + reason;
        }
    }
}
