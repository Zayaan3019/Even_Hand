package in.ac.iitm.evenhand.core;

/**
 * A marked response: a {@link DesignCell} plus the score that was awarded.
 *
 * <p>Identifiers are already pseudonymised by the time a Response exists; see
 * {@code eh-ingest}. Raw roll numbers and grader names never reach this type,
 * and never reach disk.
 *
 * @param cell     who marked what
 * @param score    the awarded score, an integer category in {@code [0, maxScore]}
 * @param maxScore the maximum available on this question
 */
public record Response(DesignCell cell, int score, int maxScore) {

    public Response {
        if (maxScore < 1)                 throw new IllegalArgumentException("maxScore must be >= 1");
        if (score < 0 || score > maxScore) throw new IllegalArgumentException(
                "score " + score + " outside [0, " + maxScore + "]");
    }

    public int student()  { return cell.student(); }
    public int question() { return cell.question(); }
    public int grader()   { return cell.grader(); }
}
