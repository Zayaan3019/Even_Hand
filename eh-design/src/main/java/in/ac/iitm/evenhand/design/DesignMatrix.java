package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;

/**
 * The marking arrangement written as a matrix, which is the form in which its
 * identifiability is a question of linear algebra rather than of intuition.
 *
 * <p>One row per marked response, one column per parameter, laid out as
 * {@code [students | questions | graders]}. The linear predictor is
 * {@code eta = theta_n - delta_i - gamma_j}, so a row carries {@code +1} in the
 * student's column and {@code -1} in the question's and the grader's. This layout is
 * also the one {@link in.ac.iitm.evenhand.core.IdentifiabilityCertificate} verifies
 * against.
 *
 * <p>Everything the audit reports comes from this matrix: its rank says how many
 * parameters the arrangement pins down, and its null space says exactly which
 * combinations of ability, difficulty and severity the data can never separate.
 *
 * <p>With a drift column, the predictor gains {@code - beta * position}: a single
 * slope for marks drifting as an assistant works deeper into the stack. Whether that
 * slope is estimable is the same row-space question as for a severity difference.
 */
public final class DesignMatrix {

    private final MarkingDesign design;
    private final int studentOffset;
    private final int questionOffset;
    private final int graderOffset;
    private final int columns;

    private final boolean collapsed;
    private final boolean drift;

    public DesignMatrix(MarkingDesign design) {
        this(design, false, false);
    }

    private DesignMatrix(MarkingDesign design, boolean collapsed, boolean drift) {
        this.design = design;
        this.collapsed = collapsed;
        this.drift = drift;
        int studentBlock = collapsed ? 1 : design.studentCount();
        this.studentOffset = 0;
        this.questionOffset = studentBlock;
        this.graderOffset = studentBlock + design.questionCount();
        this.columns = studentBlock + design.questionCount() + design.graderCount() + (drift ? 1 : 0);
    }

    public int columns()        { return columns; }
    public int studentColumn(int s)  { return collapsed ? studentOffset : studentOffset + s; }
    public int questionColumn(int q) { return questionOffset + q; }
    public int graderColumn(int g)   { return graderOffset + g; }

    public int driftColumn() {
        if (!drift) throw new IllegalStateException("this matrix has no drift column");
        return columns - 1;
    }

    /** The row contributed by one marking assignment. */
    public int[] row(DesignCell cell) {
        int[] r = new int[columns];
        r[studentColumn(cell.student())] += 1;
        r[questionColumn(cell.question())] -= 1;
        r[graderColumn(cell.grader())] -= 1;
        if (drift) {
            if (!cell.hasPosition()) {
                throw new IllegalArgumentException("a drift column needs every mark's position in its sequence");
            }
            r[driftColumn()] = -cell.position();
        }
        return r;
    }

    /**
     * The vector expressing "the difference in severity between grader {@code a} and
     * grader {@code b}". Asking whether this lies in the row space is asking whether
     * those two assistants can be compared at all.
     */
    public int[] severityContrast(int a, int b) {
        int[] v = new int[columns];
        v[graderColumn(a)] += 1;
        v[graderColumn(b)] -= 1;
        return v;
    }

    /** The vector picking out the drift slope, for the same membership question. */
    public int[] driftContrast() {
        int[] v = new int[columns];
        v[driftColumn()] = 1;
        return v;
    }

    /**
     * The same design with every student column collapsed into a single intercept.
     *
     * <p>This is how the audit tells the two failure modes apart. Collapsing the
     * students is exactly the assumption that the piles were alike in ability: if a
     * severity difference becomes estimable once you make it, the confound was with
     * student ability and randomisation is the remedy. If it stays inestimable, the
     * confound is with question difficulty, and no assumption about students touches
     * it - only a second marker on the same question does.
     *
     * <p>It is also exactly the fixed-effect part of a model in which abilities are a
     * random effect drawn from one distribution, which is the model the estimator fits.
     * So the audit certifies precisely what the estimator will assume.
     */
    public DesignMatrix withStudentsCollapsed() {
        return new DesignMatrix(design, true, drift);
    }

    /** The same design with a column for a drift slope in marking position. */
    public DesignMatrix withDriftColumn() {
        return new DesignMatrix(design, collapsed, true);
    }

    public boolean studentsCollapsed() { return collapsed; }

    public boolean hasDriftColumn() { return drift; }

    public MarkingDesign design() { return design; }
}
