package in.ac.iitm.evenhand.core;

import java.util.List;

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
 * against the design actually in hand. A verdict is evidence rather than an assertion,
 * and a stale one is caught where it is used instead of silently licensing an estimate
 * on marking nobody audited.
 *
 * <p>The quantity that matters is {@link #excessNullity()}. The linear predictor
 * {@code eta = theta_n - delta_i - gamma_j} is invariant under adding a constant to
 * every ability and every difficulty, and under adding a constant to every ability
 * and every severity. That is a null space of dimension {@link #NOMINAL_NULLITY},
 * present in every design, and cured by anchoring. Any dimension <em>beyond</em>
 * that is a real confound in this particular arrangement.
 *
 * @param rank              rank of the design matrix, over the identified parameters
 * @param parameterCount    number of free parameters before constraints
 * @param excessNullity     null dimension beyond {@link #NOMINAL_NULLITY}; zero means identified
 * @param designFingerprint {@link MarkingDesign#fingerprint()} of the design audited
 * @param nullBasis         a basis for the excess null space, for reporting which
 *                          parameters move together; may be empty
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
}
