package in.ac.iitm.evenhand.core;

/**
 * One (student, question, grader) marking assignment, with no score attached.
 *
 * <p>This is the unit the identifiability audit works on. Everything the audit
 * decides &mdash; which graders lie on a comparable scale, which severities are
 * inseparable from a question's difficulty, how precisely each severity could be
 * estimated &mdash; is a property of <em>who marked what</em>, not of the marks.
 * Keeping the score out of this type is what makes it structurally obvious that
 * the audit can run on an allocation table before any mark has been shared.
 *
 * @param student    index into {@link MarkingDesign#studentLabels()}
 * @param question   index into {@link MarkingDesign#questionLabels()}
 * @param grader     index into {@link MarkingDesign#graderLabels()}
 * @param assessment index of the marking session (quiz 1, end-sem, ...)
 * @param position   the script's place in this grader's sequence, or
 *                   {@link #NO_POSITION} when the source data did not record it
 */
public record DesignCell(int student, int question, int grader, int assessment, int position) {

    /** Sentinel for "the marking order was not recorded", which is the common case. */
    public static final int NO_POSITION = -1;

    public DesignCell {
        if (student < 0)    throw new IllegalArgumentException("student index must be >= 0");
        if (question < 0)   throw new IllegalArgumentException("question index must be >= 0");
        if (grader < 0)     throw new IllegalArgumentException("grader index must be >= 0");
        if (assessment < 0) throw new IllegalArgumentException("assessment index must be >= 0");
    }

    public DesignCell(int student, int question, int grader) {
        this(student, question, grader, 0, NO_POSITION);
    }

    public boolean hasPosition() {
        return position != NO_POSITION;
    }
}
