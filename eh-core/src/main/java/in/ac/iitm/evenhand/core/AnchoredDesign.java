package in.ac.iitm.evenhand.core;

/**
 * A marking design that has passed the audit, together with the anchoring that will
 * fix its scale. This is the only way to obtain the input the estimator accepts.
 *
 * <p>The type is the seam between the two halves of the project. It cannot be
 * constructed without a verdict, the verdict must certify that the design is
 * identified, and the certificate must be <em>for this design</em> &mdash; the
 * fingerprint is re-checked here rather than trusted. So an unaudited fit is not
 * something we remember not to do; it is something that cannot be written.
 *
 * <p>The check is deliberately at the seam and not inside the audit. The audit and
 * the estimator are owned by different people and examined separately; putting the
 * proof on one side and its verification on the other is what keeps them honest
 * about the contract between them.
 */
public final class AnchoredDesign {

    private final MarkingDesign design;
    private final AuditVerdict verdict;
    private final Anchoring anchoring;

    private AnchoredDesign(MarkingDesign design, AuditVerdict verdict, Anchoring anchoring) {
        this.design = design;
        this.verdict = verdict;
        this.anchoring = anchoring;
    }

    /**
     * @throws NotEstimableException  when the verdict refuses the design, which is a
     *                                legitimate outcome and not a bug
     * @throws IllegalArgumentException when the certificate does not belong to this
     *                                design, or contradicts itself
     */
    public static AnchoredDesign of(MarkingDesign design, AuditVerdict verdict, Anchoring anchoring) {
        IdentifiabilityCertificate cert = verdict.certificate();

        if (!cert.describes(design)) {
            throw new IllegalArgumentException(
                    "certificate is for a different marking design (fingerprint "
                    + cert.designFingerprint() + " but design is " + design.fingerprint()
                    + "); a verdict is evidence about the arrangement it was computed for");
        }
        if (verdict instanceof AuditVerdict.NotEstimable ne) {
            throw new NotEstimableException(ne);
        }
        if (verdict instanceof AuditVerdict.Estimable && !cert.isFullyIdentified()) {
            throw new IllegalArgumentException(
                    "verdict claims full identification but certificate reports excess nullity "
                    + cert.excessNullity());
        }
        if (verdict instanceof AuditVerdict.EstimableUnderExchangeability && cert.isFullyIdentified()) {
            throw new IllegalArgumentException(
                    "verdict rests on an exchangeability assumption but the design is already "
                    + "fully identified; say Estimable and claim less");
        }
        return new AnchoredDesign(design, verdict, anchoring);
    }

    public MarkingDesign design()  { return design; }
    public AuditVerdict verdict()  { return verdict; }
    public Anchoring anchoring()   { return anchoring; }

    /** Thrown when code tries to estimate from a design the audit refused. */
    public static final class NotEstimableException extends RuntimeException {
        private final transient AuditVerdict.NotEstimable verdict;

        public NotEstimableException(AuditVerdict.NotEstimable verdict) {
            super(verdict.headline());
            this.verdict = verdict;
        }

        public AuditVerdict.NotEstimable verdict() {
            return verdict;
        }
    }
}
