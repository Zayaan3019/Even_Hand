package in.ac.iitm.evenhand.core;

/**
 * The IIT Madras letter grades and their grade points, per B.Tech Ordinances and
 * Regulations R.21.1.
 *
 * <p>Two features of this scale drive the whole outcome side of the platform.
 *
 * <p>First, <strong>the points are not evenly spaced.</strong> There is no grade
 * point 5: D is 6 and E is 4. So a script misplaced across the B/C boundary costs
 * its student one grade point, while one misplaced across E/U costs four. Ranking a
 * re-grade shortlist by "probability the letter changes" therefore understates the
 * damage at exactly the boundaries where damage is worst, and we rank by expected
 * grade-point loss instead.
 *
 * <p>Second, <strong>U is a failure with consequences beyond the point count.</strong>
 * Under R.25.1 a U in a core course must be repeated. A single mark at that boundary
 * is the most consequential misplacement the system can find.
 *
 * <p>Note what is <em>not</em> here: any rule for turning marks into these letters.
 * The Ordinances prescribe none. R.22.1 and R.4.4(d) place that decision with the
 * Class Committee, and R.19.2 says the marks themselves are absolute. The mean-and-
 * sigma convention is departmental practice, not regulation, which is why the cut-off
 * rule is configuration in {@link CourseProfile} and is never compiled in.
 */
public enum LetterGrade {

    S(10, true),
    A(9,  true),
    B(8,  true),
    C(7,  true),
    D(6,  true),
    E(4,  true),
    U(0,  false),
    /**
     * Pass, on a course graded pass/fail. R.21.1 gives it no grade point at all - the
     * remarks column reads "-" rather than a number - so it does not enter the CGPA.
     *
     * <p>A pass/fail course has no bands for a correction to move a student between, so
     * the grade-transfer analysis does not apply to one and the platform says so rather
     * than reporting zero transfers as though it had looked.
     */
    P(0,  true),
    /** Fail, the pass/fail counterpart of U (R.21.1). */
    F(0,  false),
    /** Registration cancelled for want of minimum attendance (R.14.2). Not a marking outcome. */
    W(0,  false),
    /** Incomplete, subsequently changed to a pass or U in the same semester (R.21.1). */
    I(0,  false);

    private final int gradePoints;
    private final boolean pass;

    LetterGrade(int gradePoints, boolean pass) {
        this.gradePoints = gradePoints;
        this.pass = pass;
    }

    public int gradePoints() { return gradePoints; }

    /** R.21.2: a letter grade other than U/F, W or I earns the credits. */
    public boolean isPass()  { return pass; }

    /**
     * Grades that a marking correction can move a student between, best first.
     *
     * <p>Excludes P and F deliberately: a pass/fail course has no ordering for a mark to
     * move a student along. It also excludes W and I, which record attendance and
     * incompleteness rather than anything a marker did.
     */
    public static LetterGrade[] awardable() {
        return new LetterGrade[] { S, A, B, C, D, E, U };
    }

    /** True on a course graded pass/fail, where transfer analysis does not apply. */
    public boolean isPassFailOnly() {
        return this == P || this == F;
    }

    /**
     * Grade points lost by being placed in {@code awarded} when {@code deserved} was
     * the corrected grade. Negative when the student gained from the marking.
     */
    public static int pointsLost(LetterGrade deserved, LetterGrade awarded) {
        return deserved.gradePoints() - awarded.gradePoints();
    }
}
