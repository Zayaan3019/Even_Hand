package in.ac.iitm.evenhand.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Whether an assistant's severity could have moved any student relative to another,
 * and whether that is measurable. Decided from the allocation alone.
 *
 * <p>Severity harms a student only through <em>differential exposure</em>: a student
 * whose marks of record came from a different mix of assistants than a classmate's.
 * If every student met the same assistants in the same proportions &mdash; each
 * question with one marker of record, say &mdash; a harsh assistant is
 * indistinguishable from a harder question and moves nobody relative to anybody,
 * whatever the severities are. Otherwise, to first order, a student's shift relative
 * to the class is at most their spread times the range of severities, so
 * {@link #maxSpread()} bounds the damage any severity profile could do.
 *
 * <p>Where exposure differs, the question becomes whether each student-to-student
 * difference {@code (E_n - E_m) . gamma} is estimable. It is exactly when, within every
 * group of mutually comparable assistants, every student carries the same total weight
 * &mdash; which is what {@link Status#ESTIMABLE} and
 * {@link Status#UNDER_EXCHANGEABILITY} certify.
 *
 * @param status    what the allocation says
 * @param maxSpread over students, the largest number of weighted marks that would have
 *                  to change hands for that student to have the class's average mix of
 *                  assistants (half the sum of absolute differences)
 * @param meanTotal the weighted marks an average student's composite is out of, to read
 *                  the spread against
 */
public record Exposure(Status status, double maxSpread, double meanTotal) {

    public enum Status {
        /** Every student met the same assistants in the same proportions: no severity can move anyone. */
        NONE,
        /** Exposure differs, and every difference that could move a student is estimable. */
        ESTIMABLE,
        /** Exposure differs, and its effect is estimable only if the piles were alike in ability. */
        UNDER_EXCHANGEABILITY,
        /** Exposure differs, and some of its effect cannot be measured from this arrangement. */
        NOT_ESTIMABLE
    }

    public Exposure {
        Objects.requireNonNull(status, "status");
        if (!(maxSpread >= 0.0) || !Double.isFinite(maxSpread)) {
            throw new IllegalArgumentException("maxSpread must be finite and >= 0");
        }
        if (!(meanTotal >= 0.0) || !Double.isFinite(meanTotal)) {
            throw new IllegalArgumentException("meanTotal must be finite and >= 0");
        }
    }

    public boolean isNone() {
        return status == Status.NONE;
    }

    /** The label used in the scenario matrix. */
    public String shortName() {
        return switch (status) {
            case NONE -> "none";
            case ESTIMABLE -> "estimable";
            case UNDER_EXCHANGEABILITY -> "under-exch.";
            case NOT_ESTIMABLE -> "not estimable";
        };
    }

    /** One sentence in the instructor's terms. */
    public String headline() {
        String differs = "Students met different mixes of assistants (up to " + plain(maxSpread) + " of "
                + plain(meanTotal) + " weighted marks)";
        return switch (status) {
            case NONE -> "Every student's marks of record came from the same assistants in the same "
                    + "proportions, so no assistant's severity can have moved anyone relative to anyone else.";
            case ESTIMABLE -> differs + ", and every difference that could have moved a student is "
                    + "estimable from the marking.";
            case UNDER_EXCHANGEABILITY -> differs + ", and its effect is estimable only if the piles were "
                    + "alike in ability.";
            case NOT_ESTIMABLE -> differs + ", and some of its effect cannot be measured from this arrangement.";
        };
    }

    private static String plain(double x) {
        return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
