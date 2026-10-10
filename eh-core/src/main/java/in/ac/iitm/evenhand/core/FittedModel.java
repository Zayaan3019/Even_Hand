package in.ac.iitm.evenhand.core;

import java.util.List;

/**
 * Everything the measurement stage knows, in one typed record: the seam between
 * measurement ({@code eh-estimate}) and consequence ({@code eh-outcome}).
 *
 * <p>Every figure is in <strong>percentage points of a question's maximum</strong>, the
 * unit an instructor grades in. A severity of {@code +4.0} means: on a question worth
 * 10 marks, this assistant awarded about 0.4 marks less than the panel average would
 * have for the same answer. Positive is harsh, negative is lenient.
 *
 * <p>Nothing downstream reaches back into how the fit was computed, so the estimator
 * can be replaced without changing what the instructor is told.
 *
 * @param verdict      the audit verdict the fit was licensed by
 * @param anchoring    what the scale was anchored to; reported, never implicit
 * @param mean         the overall mean percentage
 * @param students     ability: how far above the class average each student scores
 * @param questions    easiness: how far above the paper average each question scores
 * @param graders      severity: how many percentage points each assistant takes off,
 *                     relative to the panel average of their comparable group
 * @param groups       graders that share a scale; severities are comparable only
 *                     within a group
 * @param residualSd   spread of what the model does not explain, per response
 * @param severityDraws bootstrap replicates of the severities, {@code [draw][grader]};
 *                     every interval downstream comes from these, so the re-check
 *                     probabilities and transfer counts come from one tested place
 * @param noEffect     the design-preserving test of "the assistants do not differ"
 * @param diagnostics  convergence
 */
public record FittedModel(AuditVerdict verdict,
                          Anchoring anchoring,
                          double mean,
                          FacetEstimates students,
                          FacetEstimates questions,
                          FacetEstimates graders,
                          List<List<Integer>> groups,
                          double residualSd,
                          double[][] severityDraws,
                          NoEffectTest noEffect,
                          FitDiagnostics diagnostics) {

    public FittedModel {
        groups = groups.stream().map(List::copyOf).toList();
    }

    /** Severity of grader {@code j} in percentage points (positive = harsh). */
    public double severity(int j) {
        return graders.estimates()[j];
    }

    /** The 2.5th and 97.5th percentiles of grader {@code j}'s bootstrap draws. */
    public double[] interval95(int j) {
        double[] column = new double[severityDraws.length];
        for (int b = 0; b < column.length; b++) column[b] = severityDraws[b][j];
        java.util.Arrays.sort(column);
        if (column.length == 0) return new double[] { severity(j), severity(j) };
        return new double[] { column[(int) Math.floor(0.025 * (column.length - 1))],
                              column[(int) Math.ceil(0.975 * (column.length - 1))] };
    }

    /**
     * Could the assistants' differences be chance? The marking is re-simulated many
     * times on the <em>same</em> allocation with every assistant equally severe, and
     * refitted; {@code pValue} is the share of those no-effect worlds whose severity
     * spread is at least the one observed.
     */
    public record NoEffectTest(double observedSpread, double pValue, int simulations) {
        public boolean rejectsAt(double alpha) {
            return pValue < alpha;
        }
    }

    /**
     * @param iterations     backfitting sweeps to convergence
     * @param maxChange      largest change on the final sweep, in percentage points
     * @param converged      whether the criterion was met rather than the cap hit
     */
    public record FitDiagnostics(int iterations, double maxChange, boolean converged) {}
}
