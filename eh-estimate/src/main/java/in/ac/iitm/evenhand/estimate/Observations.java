package in.ac.iitm.evenhand.estimate;

import in.ac.iitm.evenhand.core.Response;

import java.util.List;

/**
 * The marking flattened into parallel arrays, which is all the fit needs.
 *
 * <p>{@code y} is each mark as a percentage of its question's maximum, so a 4/5 and an
 * 8/10 are the same 80. {@code w} is the question's maximum in marks: a 20-mark answer
 * says more about a student than a 2-mark one, so it counts ten times as much. Every
 * mark is an observation of its assistant, including a second, checking mark that did
 * not count towards the grade.
 */
public record Observations(int[] student, int[] question, int[] grader, double[] y, double[] w,
                           int students, int questions, int graders) {

    public static Observations from(List<Response> responses, int students, int questions, int graders) {
        int n = responses.size();
        int[] s = new int[n], q = new int[n], g = new int[n];
        double[] y = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            Response r = responses.get(i);
            s[i] = r.student();
            q[i] = r.question();
            g[i] = r.grader();
            y[i] = 100.0 * r.score() / r.maxScore();
            w[i] = r.maxMarks();
        }
        return new Observations(s, q, g, y, w, students, questions, graders);
    }

    public int size() {
        return y.length;
    }

    /** The same marking with different marks: used to simulate the no-effect world. */
    public Observations withMarks(double[] newY) {
        return new Observations(student, question, grader, newY, w, students, questions, graders);
    }
}
