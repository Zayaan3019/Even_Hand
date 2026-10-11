package in.ac.iitm.evenhand.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the data can and cannot tell you. The platform's first output is not an
 * estimate; it is this.
 *
 * <p>The interface is sealed, so every consumer must handle every case, including the
 * refusals. That is the point: "where nothing is estimable, the platform says so and
 * stops" is a property the compiler checks, not a convention someone might forget.
 *
 * <p>Every verdict carries its {@link Exposure}: whether severity could have moved
 * anyone at all. The two are separate questions and both are reported, because a
 * severity that cannot be estimated may also be unable to do harm &mdash; pure
 * question-wise marking is exactly that case &mdash; and a severity that can be
 * estimated may be doing harm the instructor should correct.
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
 * Only the roll numbers speak to that, through {@link AllocationEvidence}.
 */
public sealed interface AuditVerdict
        permits AuditVerdict.Estimable,
                AuditVerdict.EstimableUnderExchangeability,
                AuditVerdict.PartiallyEstimable,
                AuditVerdict.NotEstimable {

    IdentifiabilityCertificate certificate();

    List<Diagnostic> diagnostics();

    /** Whether severity could have moved anyone, and whether that is measurable. */
    Exposure exposure();

    /** A one-line summary of what can be estimated, in the instructor's terms. */
    String headline();

    /** The headline together with what the allocation says about harm. */
    default String summary() {
        return headline() + " " + exposure().headline();
    }

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
     * rotates across blocks of students.
     */
    record Estimable(IdentifiabilityCertificate certificate,
                     List<Diagnostic> diagnostics,
                     Exposure exposure) implements AuditVerdict {

        public Estimable {
            if (!certificate.isFullyIdentified()) {
                throw new IllegalArgumentException(
                        "Estimable requires excessNullity == 0, got " + certificate.excessNullity());
            }
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(exposure, "exposure");
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
     * @param allocationEvidence what the roll numbers said about how piles were formed
     */
    record EstimableUnderExchangeability(IdentifiabilityCertificate certificate,
                                         List<Diagnostic> diagnostics,
                                         Exposure exposure,
                                         String assumption,
                                         String allocationEvidence) implements AuditVerdict {

        public EstimableUnderExchangeability {
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(exposure, "exposure");
        }

        @Override
        public String headline() {
            return "Assistants can be compared, but only on the assumption that their piles of "
                    + "scripts were alike in ability. " + allocationEvidence;
        }
    }

    /**
     * The assistants fall into groups: comparable within a group, never across groups.
     * Estimation proceeds within groups, and the report names every group.
     *
     * <p>The groups are reported as a partition rather than as one "reachable" set and a
     * "blocked" remainder, because when two groups are the same size neither is the
     * main one: each is a scale of its own.
     *
     * @param groups indices of the graders in each group, every grader in exactly one
     */
    record PartiallyEstimable(IdentifiabilityCertificate certificate,
                              List<Diagnostic> diagnostics,
                              Exposure exposure,
                              List<List<Integer>> groups) implements AuditVerdict {

        public PartiallyEstimable {
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(exposure, "exposure");
            List<List<Integer>> copy = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (List<Integer> group : groups) {
                if (group.isEmpty()) throw new IllegalArgumentException("a group cannot be empty");
                for (int g : group) {
                    if (!seen.add(g)) throw new IllegalArgumentException("grader " + g + " is in two groups");
                }
                copy.add(List.copyOf(group));
            }
            if (copy.size() < 2) {
                throw new IllegalArgumentException("one group is Estimable or EstimableUnderExchangeability");
            }
            if (copy.stream().noneMatch(g -> g.size() >= 2)) {
                throw new IllegalArgumentException(
                        "use NotEstimable when no two graders are mutually comparable");
            }
            groups = List.copyOf(copy);
        }

        /** The biggest group, the first of them when several tie. */
        public List<Integer> largestGroup() {
            List<Integer> best = groups.get(0);
            for (List<Integer> g : groups) {
                if (g.size() > best.size()) best = g;
            }
            return best;
        }

        @Override
        public String headline() {
            String sizes = groups.stream().map(g -> String.valueOf(g.size())).collect(Collectors.joining(", "));
            return "The assistants fall into " + groups.size() + " groups (of " + sizes + ") that can each "
                    + "be placed on a scale of their own but not on one another's; the report names them.";
        }
    }

    /**
     * Nothing about grader severity is estimable from this arrangement. The audit
     * reports what would repair it and the pipeline stops. Whether that matters is
     * the exposure's to say: it may be unable to have harmed anyone.
     */
    record NotEstimable(IdentifiabilityCertificate certificate,
                        List<Diagnostic> diagnostics,
                        Exposure exposure,
                        String reason) implements AuditVerdict {

        public NotEstimable {
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(exposure, "exposure");
        }

        @Override
        public String headline() {
            return "This marking arrangement cannot support a severity estimate: " + reason;
        }
    }
}
