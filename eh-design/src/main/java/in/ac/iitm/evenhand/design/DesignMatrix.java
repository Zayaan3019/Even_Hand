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
 * student's column and {@code -1} in the question's and the grader's.
 *
 * <p>Everything the audit reports comes from this matrix: its rank says how many
 * parameters the arrangement pins down, and its null space says exactly which
 * combinations of ability, difficulty and severity the data can never separate.
 */
public final class DesignMatrix {

    private final MarkingDesign design;
    private final int studentOffset;
    private final int questionOffset;
    private final int graderOffset;
    private final int columns;

    private final boolean collapsed;

    public DesignMatrix(MarkingDesign design) {
        this(design, false);
    }

    private DesignMatrix(MarkingDesign design, boolean collapsed) {
        this.design = design;
        this.collapsed = collapsed;
        int studentBlock = collapsed ? 1 : design.studentCount();
        this.studentOffset = 0;
        this.questionOffset = studentBlock;
        this.graderOffset = studentBlock + design.questionCount();
        this.columns = studentBlock + design.questionCount() + design.graderCount();
    }

    public int columns()        { return columns; }
    public int studentColumn(int s)  { return collapsed ? studentOffset : studentOffset + s; }
    public int questionColumn(int q) { return questionOffset + q; }
    public int graderColumn(int g)   { return graderOffset + g; }

    /** The row contributed by one marking assignment. */
    public int[] row(DesignCell cell) {
        int[] r = new int[columns];
        r[studentColumn(cell.student())] += 1;
        r[questionColumn(cell.question())] -= 1;
        r[graderColumn(cell.grader())] -= 1;
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

    /**
     * The same design with every student column collapsed into a single intercept.
     *
     * <p>This is how the audit tells the two failure modes apart. Collapsing the
     * students is exactly the assumption that the piles were alike in ability: if a
     * severity difference becomes estimable once you make it, the confound was with
     * student ability and randomisation is the remedy. If it stays inestimable, the
     * confound is with question difficulty, and no assumption about students touches
     * it - only a second marker on the same question does.
     */
    public DesignMatrix withStudentsCollapsed() {
        return new DesignMatrix(design, true);
    }

    public boolean studentsCollapsed() { return collapsed; }

    public MarkingDesign design() { return design; }
}
