package in.ac.iitm.evenhand.outcome;

import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;

import java.util.List;

/**
 * A student's course total out of 100: each assessment's percentage, weighted as the
 * course weights it (R.19.1 makes a course grade a weighted composite).
 *
 * <p>Only marks of record count. A response with two marks of record counts as their
 * average; a second, checking mark does not count at all. A student with no marks in an
 * assessment scores zero in it, as an absentee would.
 */
public final class Composite {

    private final int students;
    private final int[] assessmentOf;      // per question
    private final double[] questionMax;    // per question, in marks
    private final double[] assessmentMax;  // per assessment, in marks
    private final double[] weights;        // per assessment, summing to 1
    private final List<Response> record;

    public Composite(List<Response> responses, MarkingDesign design, double[] assessmentWeights) {
        this.students = design.studentCount();
        int questions = design.questionCount();
        int assessments = design.assessmentCount();
        if (assessmentWeights.length != assessments) {
            throw new IllegalArgumentException("need one weight per assessment: " + assessments
                    + " assessments, " + assessmentWeights.length + " weights");
        }
        this.assessmentOf = new int[questions];
        this.questionMax = new double[questions];
        for (Response r : responses) {
            assessmentOf[r.question()] = r.cell().assessment();
            questionMax[r.question()] = r.maxMarks();
        }
        this.assessmentMax = new double[assessments];
        for (int q = 0; q < questions; q++) assessmentMax[assessmentOf[q]] += questionMax[q];

        double total = 0;
        for (double w : assessmentWeights) {
            if (!(w >= 0)) throw new IllegalArgumentException("weights must be >= 0");
            total += w;
        }
        if (total <= 0) throw new IllegalArgumentException("at least one assessment must carry weight");
        this.weights = new double[assessments];
        for (int a = 0; a < assessments; a++) weights[a] = assessmentWeights[a] / total;
        this.record = responses.stream().filter(r -> r.cell().ofRecord()).toList();
    }

    /** Totals as marked. */
    public double[] asMarked() {
        return totals(null);
    }

    /**
     * Totals had every assistant marked like the panel average: each mark gets back the
     * severity of whoever wrote it, kept within zero and full marks.
     *
     * @param severity per assistant, percentage points of a question's maximum (positive = harsh)
     */
    public double[] corrected(double[] severity) {
        return totals(severity);
    }

    private double[] totals(double[] severity) {
        int questions = questionMax.length;
        double[] sum = new double[students * questions];
        int[] count = new int[students * questions];
        for (Response r : record) {
            double marks = r.marks();
            if (severity != null) {
                marks += severity[r.grader()] * r.maxMarks() / 100.0;
                marks = Math.max(0, Math.min(r.maxMarks(), marks));
            }
            int unit = r.student() * questions + r.question();
            sum[unit] += marks;
            count[unit]++;
        }
        double[][] byAssessment = new double[students][assessmentMax.length];
        for (int s = 0; s < students; s++) {
            for (int q = 0; q < questions; q++) {
                int unit = s * questions + q;
                if (count[unit] > 0) byAssessment[s][assessmentOf[q]] += sum[unit] / count[unit];
            }
        }
        double[] out = new double[students];
        for (int s = 0; s < students; s++) {
            for (int a = 0; a < assessmentMax.length; a++) {
                if (assessmentMax[a] > 0) out[s] += 100.0 * weights[a] * byAssessment[s][a] / assessmentMax[a];
            }
        }
        return out;
    }

    /** Marks each question is worth in the composite: what decides who was exposed to whom. */
    public double[] questionWeights() {
        double[] out = new double[questionMax.length];
        for (int q = 0; q < out.length; q++) {
            double a = assessmentMax[assessmentOf[q]];
            out[q] = a == 0 ? 0 : 100.0 * weights[assessmentOf[q]] * questionMax[q] / a;
        }
        return out;
    }
}
