package in.ac.iitm.evenhand.core;

/**
 * A marked response: a {@link DesignCell} plus the score that was awarded.
 *
 * <p>Identifiers are already pseudonymised by the time a Response exists; see
 * {@code eh-ingest}. Raw roll numbers and grader names never reach this type.
 *
 * <p>Scores are held as whole steps, because the measurement model treats each
 * attainable mark as a category. A question marked in half marks has two steps per
 * mark, so 2.5 out of 5 is held as 5 out of 10 with {@code stepsPerMark == 2}; the
 * marks themselves are always recoverable from {@link #marks()}.
 *
 * @param cell         who marked what
 * @param score        the awarded score, in steps, in {@code [0, maxScore]}
 * @param maxScore     the maximum available on this question, in steps
 * @param stepsPerMark how many steps make one mark: 1 for whole marks, 2 for half marks
 */
public record Response(DesignCell cell, int score, int maxScore, int stepsPerMark) {

    public Response {
        if (stepsPerMark < 1)             throw new IllegalArgumentException("stepsPerMark must be >= 1");
        if (maxScore < 1)                 throw new IllegalArgumentException("maxScore must be >= 1");
        if (score < 0 || score > maxScore) throw new IllegalArgumentException(
                "score " + score + " outside [0, " + maxScore + "]");
    }

    /** A response scored in whole marks. */
    public Response(DesignCell cell, int score, int maxScore) {
        this(cell, score, maxScore, 1);
    }

    /** The score in marks, as the assistant wrote it. */
    public double marks() {
        return score / (double) stepsPerMark;
    }

    /** The question's maximum in marks. */
    public double maxMarks() {
        return maxScore / (double) stepsPerMark;
    }

    public int student()  { return cell.student(); }
    public int question() { return cell.question(); }
    public int grader()   { return cell.grader(); }
}
