package in.ac.iitm.evenhand.core;

import java.util.List;

/**
 * Estimates for one facet, in logits, each with its standard error.
 *
 * <p>Every figure carries an interval. A severity estimate without one is unusable,
 * and worse than unusable if it is about a person: an assistant who marked thirty
 * scripts must be visibly less precisely estimated than one who marked three hundred,
 * or the output invites a comparison the data does not support.
 *
 * @param labels    pseudonymised element labels, parallel to the arrays
 * @param estimates parameter estimates in logits
 * @param standardErrors standard errors from the observed information
 * @param counts    observations behind each estimate, which is what makes the
 *                  differing precision legible
 * @param extreme   elements with a zero or perfect score, whose estimates are not
 *                  finite and are reported as bounds rather than values
 */
public record FacetEstimates(Facet facet,
                             List<String> labels,
                             double[] estimates,
                             double[] standardErrors,
                             int[] counts,
                             boolean[] extreme) {

    public FacetEstimates {
        labels = List.copyOf(labels);
        int n = labels.size();
        if (estimates.length != n || standardErrors.length != n || counts.length != n || extreme.length != n) {
            throw new IllegalArgumentException("all arrays must have one entry per label");
        }
    }

    public int size() {
        return labels.size();
    }

    /** Half-width of a nominal 95% interval for element {@code i}. */
    public double halfWidth95(int i) {
        return 1.959963985 * standardErrors[i];
    }

    /** Spread of the estimates: the quantity the permutation test puts a null under. */
    public double spread() {
        int n = size();
        if (n < 2) return 0.0;
        double mean = 0.0;
        for (int i = 0; i < n; i++) mean += estimates[i];
        mean /= n;
        double ss = 0.0;
        for (int i = 0; i < n; i++) {
            double d = estimates[i] - mean;
            ss += d * d;
        }
        return Math.sqrt(ss / (n - 1));
    }
}
