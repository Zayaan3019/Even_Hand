package in.ac.iitm.evenhand.core;

import java.util.List;
import java.util.Optional;

/**
 * The single typed record where the two halves of this project meet.
 *
 * <p>The first half &mdash; profiling, the audit, the measurement model and drift
 * &mdash; produces it. The second half &mdash; grading-rule propagation, the
 * shortlist, the linking design and the instructor's views &mdash; consumes it and
 * nothing else. Neither half reaches around this record into the other's internals,
 * which is what makes it possible for each of us to be examined on the other's
 * modules.
 *
 * @param anchoring     what the scale was anchored to; reported, never implicit
 * @param students      ability estimates
 * @param questions     difficulty estimates
 * @param graderSessions severity estimates, one per (grader, marking session),
 *                      because severity is a property of a marking session rather
 *                      than a standing verdict on a person
 * @param covariance    covariance of the estimated parameters on the identified
 *                      subspace; every interval downstream is drawn from this, so
 *                      the shortlist's probabilities and the transfer counts come
 *                      from one place that can be tested once
 * @param scale         how to say a logit in marks, which is the only unit an
 *                      instructor grades in
 * @param drift         position-in-pile effect, when the marking order was recorded
 * @param diagnostics   convergence and fit
 */
public record FittedModel(AuditVerdict verdict,
                          Anchoring anchoring,
                          FacetEstimates students,
                          FacetEstimates questions,
                          FacetEstimates graderSessions,
                          double[][] covariance,
                          ScoreScale scale,
                          Optional<DriftEstimate> drift,
                          FitDiagnostics diagnostics) {

    /**
     * Converts logits to the units the instructor grades in.
     *
     * <p>The conversion is not a constant multiplier: the expected-score curve is
     * steepest in the middle, so the same severity costs a mid-range student more
     * marks than a very strong or very weak one. {@code marksPerLogitAtMean} is the
     * slope at the cohort mean and is what the headline figure is quoted in; the
     * per-student effect is computed exactly rather than from this slope.
     */
    public record ScoreScale(double marksPerLogitAtMean, double totalMarksAvailable) {}

    /**
     * Whether marks fall as a grader works deeper into a pile.
     *
     * @param slopePerHundred change in logits over one hundred scripts
     * @param standardError   its standard error
     * @param likelihoodRatioP p-value of the test against no drift
     */
    public record DriftEstimate(double slopePerHundred, double standardError, double likelihoodRatioP) {}

    /**
     * @param iterations     iterations to convergence
     * @param maxParamChange largest parameter change on the final iteration
     * @param converged      whether the criterion was met rather than the cap hit
     * @param misfitting     elements whose fit statistics say the model does not
     *                       describe them; an erratic grader is a different finding
     *                       from a harsh one and must not be reported as severity
     */
    public record FitDiagnostics(int iterations,
                                 double maxParamChange,
                                 boolean converged,
                                 List<String> misfitting) {
        public FitDiagnostics {
            misfitting = List.copyOf(misfitting);
        }
    }
}
