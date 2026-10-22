package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.numerics.ExactRowSpace;

import java.util.Optional;

/**
 * Does a late script lose, and could we tell? Answered from the marking order alone.
 *
 * <p>The question is the audit's own, asked of one more parameter: add a column for a
 * drift slope in marking position and ask whether the vector picking it out lies in
 * the row space &mdash; exactly, and then again with the students collapsed.
 *
 * <p>It exposes a trap in question-wise marking. If every assistant works through the
 * stack in the same order, usually roll-number order, a script's position is the same
 * on every question, so position moves with the student. The same students are then
 * late on <em>every</em> question, so any drift accumulates for them, and yet drift can
 * only be separated from ability by assuming stack order is unrelated to it. Having
 * alternate assistants reverse the stack costs nothing, identifies drift from the
 * marking alone, and balances who is late.
 */
public final class DriftAudit {

    private DriftAudit() {
    }

    public enum Status {
        /** Some mark has no recorded position, so drift cannot be tested at all. */
        NOT_RECORDED,
        /** The drift slope is estimable from the marking alone. */
        ESTIMABLE,
        /** Estimable only if stack order is unrelated to ability. */
        UNDER_EXCHANGEABILITY,
        /** Not separable from the other effects even then. */
        NOT_ESTIMABLE;

        /** The label used in the scenario matrix. */
        public String shortName() {
            return switch (this) {
                case NOT_RECORDED -> "not recorded";
                case ESTIMABLE -> "estimable";
                case UNDER_EXCHANGEABILITY -> "under-exch.";
                case NOT_ESTIMABLE -> "not estimable";
            };
        }
    }

    /** What the audit found, with the diagnostic to show when there is something to fix. */
    public record Result(Status status, Optional<Diagnostic> diagnostic) {}

    public static Result audit(MarkingDesign design) {
        if (design.size() == 0 || !design.cells().stream().allMatch(DesignCell::hasPosition)) {
            return new Result(Status.NOT_RECORDED, Optional.empty());
        }
        DesignMatrix full = new DesignMatrix(design).withDriftColumn();
        if (rowSpaceOf(full, design).contains(full.driftContrast())) {
            return new Result(Status.ESTIMABLE, Optional.empty());
        }
        DesignMatrix collapsed = full.withStudentsCollapsed();
        if (rowSpaceOf(collapsed, design).contains(collapsed.driftContrast())) {
            return new Result(Status.UNDER_EXCHANGEABILITY, Optional.of(Diagnostic.warning(
                    Diagnostic.Code.EH013_DRIFT_RESTS_ON_STACK_ORDER,
                    "A script's place in the marking order moves with the student rather than varying "
                    + "across questions, typically because every TA worked through the stack in the "
                    + "same order. So whether marks drift can only be measured by assuming that order is "
                    + "unrelated to ability, and the same students were late on every question, so any "
                    + "drift accumulates for them.",
                    design.graderLabels(),
                    "Have alternate TAs work through the stack in reverse order. It costs nothing, "
                    + "makes drift measurable from the marking alone, and spreads who is marked late.")));
        }
        return new Result(Status.NOT_ESTIMABLE, Optional.of(Diagnostic.warning(
                Diagnostic.Code.EH014_DRIFT_NOT_IDENTIFIED,
                "Marking position cannot be separated from the other effects in this arrangement, even "
                + "assuming the order is unrelated to ability, so drift is not measurable.",
                design.graderLabels(),
                "Record each TA's genuine marking order, and have alternate TAs reverse the stack.")));
    }

    private static ExactRowSpace rowSpaceOf(DesignMatrix matrix, MarkingDesign design) {
        ExactRowSpace space = new ExactRowSpace(matrix.columns());
        for (DesignCell cell : design.cells()) {
            space.addRow(matrix.row(cell));
        }
        return space;
    }
}
