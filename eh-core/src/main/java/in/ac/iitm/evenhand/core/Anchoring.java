package in.ac.iitm.evenhand.core;

/**
 * The constraint that fixes the scale. The parameters are determined only up to an
 * additive shift, so this is imposed deliberately and reported, rather than being
 * whatever the optimiser happened to land on.
 *
 * @param kind        which constraint was used
 * @param description how to say it to an instructor, e.g. "severities average zero,
 *                    so a grader's figure is read against the panel"
 */
public record Anchoring(Kind kind, String description) {

    public enum Kind {
        /** Mean severity set to zero: each figure is read against the panel average. */
        GRADERS_CENTRED,
        /** Mean question difficulty set to zero. */
        QUESTIONS_CENTRED,
        /** Both of the above; the usual choice for a connected design. */
        GRADERS_AND_QUESTIONS_CENTRED,
        /** A nominated grader is fixed at zero, for when one marker is the reference. */
        REFERENCE_GRADER
    }

    public static Anchoring standard() {
        return new Anchoring(Kind.GRADERS_AND_QUESTIONS_CENTRED,
                "Severities average zero and question difficulties average zero, so each "
                + "grader's figure is read against the panel and each question against the paper.");
    }
}
