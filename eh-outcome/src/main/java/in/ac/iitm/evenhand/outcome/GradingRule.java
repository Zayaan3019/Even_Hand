package in.ac.iitm.evenhand.outcome;

import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.LetterGrade;

import java.util.Arrays;
import java.util.Map;

/**
 * Turns course totals into IIT Madras letter grades under the course's own rule.
 *
 * <p>The Ordinances fix the letters and their points (R.21.1) but not the rule, so the
 * rule is the instructor's {@link CutoffPolicy}. Under the usual relative rule the
 * boundaries are recomputed from whichever totals are being graded, because correcting
 * the marks also moves the class mean and spread.
 */
public final class GradingRule {

    private static final LetterGrade[] BEST_FIRST = { LetterGrade.S, LetterGrade.A, LetterGrade.B,
            LetterGrade.C, LetterGrade.D, LetterGrade.E };

    private GradingRule() {
    }

    public static LetterGrade[] grade(CutoffPolicy policy, double[] totals) {
        return switch (policy.kind()) {
            case ABSOLUTE -> byThresholds(policy.absolute(), totals);
            case SIGMA_BANDS, TOP_FRACTION -> relative(policy, totals);
            case INSTRUCTOR_BANDS -> throw new UnsupportedOperationException(
                    "Hand-drawn bands have no counterfactual. Give the thresholds you drew as an absolute rule.");
        };
    }

    /** The lower edge of each grade under this rule for these totals, best grade first. */
    public static Map<LetterGrade, Double> boundaries(CutoffPolicy policy, double[] totals) {
        if (policy.kind() == CutoffPolicy.Kind.ABSOLUTE) return policy.absolute();
        double mean = mean(totals), sd = sd(totals, mean);
        java.util.LinkedHashMap<LetterGrade, Double> out = new java.util.LinkedHashMap<>();
        for (LetterGrade g : BEST_FIRST) {
            Double offset = policy.bandOffsets().get(g);
            if (offset != null) out.put(g, mean + offset * sd);
        }
        if (policy.sBandPolicy() != CutoffPolicy.SBandPolicy.SIGMA_BINDS && policy.topFraction() > 0) {
            out.put(LetterGrade.S, topFractionCut(totals, policy.topFraction()));
        }
        return out;
    }

    private static LetterGrade[] relative(CutoffPolicy policy, double[] totals) {
        double mean = mean(totals), sd = sd(totals, mean);
        LetterGrade[] out = new LetterGrade[totals.length];
        for (int i = 0; i < totals.length; i++) {
            out[i] = LetterGrade.U;
            for (LetterGrade g : BEST_FIRST) {
                if (g == LetterGrade.S && policy.sBandPolicy() == CutoffPolicy.SBandPolicy.TOP_FRACTION_BINDS) {
                    continue;   // S is decided by the share below, not by the sigma line
                }
                Double offset = policy.bandOffsets().get(g);
                if (offset != null && totals[i] >= mean + offset * sd) {
                    out[i] = g;
                    break;
                }
            }
        }
        if (policy.sBandPolicy() != CutoffPolicy.SBandPolicy.SIGMA_BINDS && policy.topFraction() > 0) {
            double cut = topFractionCut(totals, policy.topFraction());
            for (int i = 0; i < totals.length; i++) {
                if (totals[i] >= cut) out[i] = LetterGrade.S;
            }
        }
        return out;
    }

    /**
     * The total at which the top share begins: the {@code ceil(fraction * n)}-th highest.
     * Students tied at the cut are all admitted, rather than broken by file order.
     */
    static double topFractionCut(double[] totals, double fraction) {
        double[] sorted = totals.clone();
        Arrays.sort(sorted);
        int k = Math.max(1, (int) Math.ceil(fraction * sorted.length));
        return sorted[sorted.length - k];
    }

    private static LetterGrade[] byThresholds(Map<LetterGrade, Double> thresholds, double[] totals) {
        LetterGrade[] out = new LetterGrade[totals.length];
        for (int i = 0; i < totals.length; i++) {
            out[i] = LetterGrade.U;
            for (LetterGrade g : BEST_FIRST) {
                Double t = thresholds.get(g);
                if (t != null && totals[i] >= t) {
                    out[i] = g;
                    break;
                }
            }
        }
        return out;
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return x.length == 0 ? 0 : s / x.length;
    }

    static double sd(double[] x, double mean) {
        double ss = 0;
        for (double v : x) ss += (v - mean) * (v - mean);
        return x.length == 0 ? 0 : Math.sqrt(ss / x.length);
    }
}
