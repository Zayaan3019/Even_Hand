package in.ac.iitm.evenhand.core;

/**
 * One (student, question, grader) marking assignment, with no score attached.
 *
 * <p>This is the unit the identifiability audit works on. Everything the audit
 * decides &mdash; which graders lie on a comparable scale, which severities are
 * inseparable from a question's difficulty, who could have been harmed by whose
 * marking &mdash; is a property of <em>who marked what</em>, not of the marks.
 * Keeping the score out of this type is what makes it structurally obvious that
 * the audit can run on an allocation table before any mark has been shared.
 *
 * @param student    index into {@link MarkingDesign#studentLabels()}
 * @param question   index into {@link MarkingDesign#questionLabels()}
 * @param grader     index into {@link MarkingDesign#graderLabels()}
 * @param assessment index of the marking session (quiz 1, end-sem, ...)
 * @param position   the script's place in this grader's sequence, or
 *                   {@link #NO_POSITION} when the source data did not record it
 * @param ofRecord   whether this is a mark that counts towards the student's grade.
 *                   A second mark taken to check the first is not. Where a response
 *                   has several marks of record, they are taken as averaged. Every
 *                   mark, of record or not, is an observation of its grader; only
 *                   marks of record decide who could have been harmed
 */
public record DesignCell(int student, int question, int grader, int assessment, int position, boolean ofRecord) {

    /** Sentinel for "the marking order was not recorded", which is the common case. */
    public static final int NO_POSITION = -1;

    public DesignCell {
        if (student < 0)    throw new IllegalArgumentException("student index must be >= 0");
        if (question < 0)   throw new IllegalArgumentException("question index must be >= 0");
        if (grader < 0)     throw new IllegalArgumentException("grader index must be >= 0");
        if (assessment < 0) throw new IllegalArgumentException("assessment index must be >= 0");
        if (position < NO_POSITION) throw new IllegalArgumentException("position must be >= 0, or NO_POSITION");
    }

    /** A mark of record. */
    public DesignCell(int student, int question, int grader, int assessment, int position) {
        this(student, question, grader, assessment, position, true);
    }

    /** A mark of record in the first assessment, marking order unknown. */
    public DesignCell(int student, int question, int grader) {
        this(student, question, grader, 0, NO_POSITION, true);
    }

    public boolean hasPosition() {
        return position != NO_POSITION;
    }

    /** The same assignment, recorded as a second mark that does not count towards the grade. */
    public DesignCell asSecondMark() {
        return new DesignCell(student, question, grader, assessment, position, false);
    }
}
