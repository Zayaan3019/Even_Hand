package in.ac.iitm.evenhand.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The audit's proof, carried with its verdict.
 *
 * <p>The audit and the estimation do not run at the same time. The audit needs only
 * the marking arrangement, so it runs as soon as the allocation table exists --- often
 * weeks before any mark is available --- and its verdict has to survive until there is
 * something to estimate. A verdict that travels that far cannot be a bare boolean: by
 * the time it is used, the marking it described may have changed, because a script was
 * re-marked, a row was added, or a different export was produced.
 *
 * <p>So the audit emits this certificate and {@link AnchoredDesign#of} re-checks it
 * against the design actually in hand, with {@link #contradictionWith}. A verdict is
 * evidence rather than an assertion, and a stale or wrong one is caught where it is
 * used instead of silently licensing an estimate on marking nobody audited.
 *
 * <p>The quantity that matters is {@link #excessNullity()}. The linear predictor
 * {@code eta = theta_n - delta_i - gamma_j} is invariant under adding a constant to
 * every ability and every difficulty, and under adding a constant to every ability
 * and every severity. That is a null space of dimension {@link #NOMINAL_NULLITY},
 * present in every design, and cured by anchoring. Any dimension <em>beyond</em>
 * that is a real confound in this particular arrangement.
 *
 * <p>Parameters are laid out as the audit lays them out: students, then questions,
 * then graders. A marked response contributes {@code +1} for its student and
 * {@code -1} for its question and its grader.
 *
 * @param rank              rank of the design matrix
 * @param parameterCount    number of free parameters before constraints
 * @param excessNullity     null dimension beyond {@link #NOMINAL_NULLITY}; zero means identified
 * @param designFingerprint {@link MarkingDesign#fingerprint()} of the design audited
 * @param nullBasis         a basis for the whole null space, which says which parameters
 *                          move together without changing any prediction
 */
public record IdentifiabilityCertificate(int rank,
                                         int parameterCount,
                                         int excessNullity,
                                         long designFingerprint,
                                         List<double[]> nullBasis) {

    /**
     * The null dimension every three-facet additive design has, and which anchoring
     * removes: one shift shared by abilities and difficulties, one shared by
     * abilities and severities.
     */
    public static final int NOMINAL_NULLITY = 2;

    /** Primes below 2^31, so the product of two residues always fits in a long. */
    private static final long PRIME_A = 2_147_483_647L;
    private static final long PRIME_B = 1_000_000_007L;

    /** A null direction may leave a residual this small relative to its largest entry. */
    private static final double ANNIHILATION_TOLERANCE = 1e-8;

    public IdentifiabilityCertificate {
        if (excessNullity < 0) throw new IllegalArgumentException("excessNullity must be >= 0");
        if (rank < 0)          throw new IllegalArgumentException("rank must be >= 0");
        nullBasis = List.copyOf(nullBasis);
    }

    /** True when the only indeterminacy left is the one anchoring fixes. */
    public boolean isFullyIdentified() {
        return excessNullity == 0;
    }

    public boolean describes(MarkingDesign design) {
        return design.fingerprint() == designFingerprint;
    }

    /**
     * Checks this certificate against a design instead of trusting it.
     *
     * <p>A certificate can be wrong in either direction, so both are checked, and
     * neither check repeats the audit's elimination.
     *
     * <ul>
     *   <li><strong>Claiming too much identification.</strong> The rank of the design's
     *       information matrix modulo a prime can never exceed its true rank, because a
     *       minor that vanishes over the integers vanishes modulo anything. So if that
     *       modular rank falls short of the claimed rank under two primes, the certificate
     *       claims more than the marking supports and is rejected. This is exact integer
     *       arithmetic; no tolerance decides it.</li>
     *   <li><strong>Claiming too much confounding.</strong> Every vector of the null basis
     *       must leave every marked response's prediction unchanged, and the vectors must
     *       be independent. That proves the null space is at least as large as claimed,
     *       so the rank is no larger than claimed.</li>
     * </ul>
     *
     * <p>Together the two pin the rank exactly. A failure of the modular check under an
     * unlucky prime would refuse a correct certificate, never accept a wrong one.
     *
     * @return the first contradiction found, or empty when the certificate holds
     */
    public Optional<String> contradictionWith(MarkingDesign design) {
        if (!describes(design)) {
            return Optional.of("certificate is for a different marking design (fingerprint "
                    + designFingerprint + " but design is " + design.fingerprint() + ")");
        }
        int students = design.studentCount();
        int questions = design.questionCount();
        int columns = students + questions + design.graderCount();
        if (parameterCount != columns) {
            return Optional.of("certificate counts " + parameterCount + " parameters but the design has "
                    + columns);
        }
        if (rank + NOMINAL_NULLITY + excessNullity != columns) {
            return Optional.of("rank " + rank + " and excess nullity " + excessNullity
                    + " do not account for " + columns + " parameters");
        }

        int confirmed = modularRank(design, PRIME_A);
        if (confirmed < rank) {
            confirmed = Math.max(confirmed, modularRank(design, PRIME_B));
        }
        if (confirmed > rank) {
            return Optional.of("certificate claims rank " + rank + " but the design has rank at least "
                    + confirmed + ", so it claims more confounding than the marking contains");
        }
        if (confirmed < rank) {
            return Optional.of("certificate claims rank " + rank + " but only " + confirmed
                    + " can be confirmed from the design in hand, so it claims more than the marking supports");
        }

        if (nullBasis.size() != columns - rank) {
            return Optional.of("null basis has " + nullBasis.size() + " vectors, but a design of rank " + rank
                    + " over " + columns + " parameters has a null space of dimension " + (columns - rank));
        }
        return checkNullBasis(design, students, questions, columns);
    }

    private Optional<String> checkNullBasis(MarkingDesign design, int students, int questions, int columns) {
        List<double[]> accepted = new ArrayList<>();
        for (int k = 0; k < nullBasis.size(); k++) {
            double[] v = nullBasis.get(k);
            if (v.length != columns) {
                return Optional.of("null basis vector " + k + " has " + v.length + " entries, expected " + columns);
            }
            double scale = 0.0;
            for (double x : v) scale = Math.max(scale, Math.abs(x));
            if (!(scale > 0.0) || !Double.isFinite(scale)) {
                return Optional.of("null basis vector " + k + " is zero or not finite");
            }
            for (DesignCell c : design.cells()) {
                double residual = v[c.student()] - v[students + c.question()]
                        - v[students + questions + c.grader()];
                if (Math.abs(residual) > ANNIHILATION_TOLERANCE * scale) {
                    return Optional.of("null basis vector " + k + " changes the prediction for "
                            + design.studentLabels().get(c.student()) + " on "
                            + design.questionLabels().get(c.question()) + " marked by "
                            + design.graderLabels().get(c.grader())
                            + ", so it is not a null direction of this design");
                }
            }
            // Modified Gram-Schmidt: whatever is left after removing the directions already
            // accepted must not vanish, or the basis is overstating the confounding.
            double[] w = new double[columns];
            for (int i = 0; i < columns; i++) w[i] = v[i] / scale;
            double before = norm(w);
            for (double[] u : accepted) {
                double d = dot(w, u);
                for (int i = 0; i < columns; i++) w[i] -= d * u[i];
            }
            double after = norm(w);
            if (after <= 1e-9 * before) {
                return Optional.of("null basis vector " + k
                        + " depends on the others, so the basis overstates the confounding");
            }
            for (int i = 0; i < columns; i++) w[i] /= after;
            accepted.add(w);
        }
        return Optional.empty();
    }

    /**
     * Rank of {@code X'X} modulo {@code p}, where {@code X} is the design matrix. It is a
     * lower bound on the rank of {@code X} over the rationals, and equal to it except for
     * primes that divide every maximal non-vanishing minor.
     */
    private static int modularRank(MarkingDesign design, long p) {
        int students = design.studentCount();
        int questions = design.questionCount();
        int n = students + questions + design.graderCount();
        long[][] g = new long[n][n];
        int[] index = new int[3];
        int[] sign = {1, -1, -1};
        for (DesignCell c : design.cells()) {
            index[0] = c.student();
            index[1] = students + c.question();
            index[2] = students + questions + c.grader();
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    g[index[a]][index[b]] += sign[a] * sign[b];
                }
            }
        }
        for (long[] row : g) {
            for (int j = 0; j < n; j++) row[j] = Math.floorMod(row[j], p);
        }

        int rank = 0;
        for (int col = 0; col < n && rank < n; col++) {
            int pivot = -1;
            for (int r = rank; r < n; r++) {
                if (g[r][col] != 0) {
                    pivot = r;
                    break;
                }
            }
            if (pivot < 0) continue;
            long[] swap = g[rank];
            g[rank] = g[pivot];
            g[pivot] = swap;

            long inverse = power(g[rank][col], p - 2, p);
            for (int r = rank + 1; r < n; r++) {
                if (g[r][col] == 0) continue;
                long factor = g[r][col] * inverse % p;
                long[] target = g[r];
                long[] source = g[rank];
                for (int j = col; j < n; j++) {
                    long value = (target[j] - factor * source[j]) % p;
                    target[j] = value < 0 ? value + p : value;
                }
            }
            rank++;
        }
        return rank;
    }

    private static long power(long base, long exponent, long p) {
        long result = 1;
        long b = base % p;
        long e = exponent;
        while (e > 0) {
            if ((e & 1) == 1) result = result * b % p;
            b = b * b % p;
            e >>= 1;
        }
        return result;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }
}
