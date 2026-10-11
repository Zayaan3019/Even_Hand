package in.ac.iitm.evenhand.estimate;

import java.util.List;

/**
 * Fits the additive marking model by backfitting.
 *
 * <pre>
 *   mark %  =  mean  +  ability[student]  +  easiness[question]  -  severity[assistant]  +  noise
 * </pre>
 *
 * <p>Each sweep updates one facet at a time, holding the others fixed: a student's
 * ability is the weighted average of what is left of their marks once the paper and
 * their assistants are accounted for, and likewise for questions and assistants. Every
 * update is a weighted average, so the whole method fits on a whiteboard. It stops when
 * no estimate moves by more than {@link Settings#tolerance()} in a sweep, and reports
 * whether it got there.
 *
 * <p>Abilities and severities are shrunk towards zero (see {@link Settings}); question
 * easiness is not, because every question is seen by the whole class.
 *
 * <p><b>Anchoring.</b> Easiness averages zero over the questions, and severity averages
 * zero within each group of assistants that share a scale, so each assistant is read
 * against the panel they can be compared with.
 */
final class Backfit {

    record Result(double mean, double[] ability, double[] easiness, double[] severity,
                  double residualSd, int iterations, double maxChange, boolean converged) {

        double fitted(Observations o, int i) {
            return mean + ability[o.student()[i]] + easiness[o.question()[i]] - severity[o.grader()[i]];
        }
    }

    private Backfit() {
    }

    static Result fit(Observations o, List<List<Integer>> groups, Settings settings) {
        int n = o.size();
        double[] a = new double[o.students()];
        double[] e = new double[o.questions()];
        double[] s = new double[o.graders()];

        double mean = 0, totalW = 0;
        for (int i = 0; i < n; i++) {
            mean += o.w()[i] * o.y()[i];
            totalW += o.w()[i];
        }
        mean /= totalW;

        double[] numA = new double[a.length], denA = new double[a.length];
        double[] numE = new double[e.length], denE = new double[e.length];
        double[] numS = new double[s.length], denS = new double[s.length];

        int iteration = 0;
        double maxChange = Double.POSITIVE_INFINITY;
        while (iteration < settings.maxIterations() && maxChange > settings.tolerance()) {
            iteration++;
            maxChange = 0;

            // 1. Students: what is left of each student's marks, shrunk towards the class.
            java.util.Arrays.fill(numA, 0);
            java.util.Arrays.fill(denA, 0);
            for (int i = 0; i < n; i++) {
                int st = o.student()[i];
                numA[st] += o.w()[i] * (o.y()[i] - mean - e[o.question()[i]] + s[o.grader()[i]]);
                denA[st] += o.w()[i];
            }
            for (int k = 0; k < a.length; k++) {
                double next = denA[k] == 0 ? 0 : numA[k] / (denA[k] + settings.studentShrinkage());
                maxChange = Math.max(maxChange, Math.abs(next - a[k]));
                a[k] = next;
            }

            // 2. Questions: what is left of each question's marks; centred on the paper.
            java.util.Arrays.fill(numE, 0);
            java.util.Arrays.fill(denE, 0);
            for (int i = 0; i < n; i++) {
                int q = o.question()[i];
                numE[q] += o.w()[i] * (o.y()[i] - mean - a[o.student()[i]] + s[o.grader()[i]]);
                denE[q] += o.w()[i];
            }
            double eBar = 0;
            int eCount = 0;
            for (int k = 0; k < e.length; k++) {
                if (denE[k] > 0) {
                    eBar += numE[k] / denE[k];
                    eCount++;
                }
            }
            eBar = eCount == 0 ? 0 : eBar / eCount;
            for (int k = 0; k < e.length; k++) {
                double next = denE[k] == 0 ? 0 : numE[k] / denE[k] - eBar;
                maxChange = Math.max(maxChange, Math.abs(next - e[k]));
                e[k] = next;
            }

            // 3. Assistants: how far below the model each assistant's marks fall, shrunk
            //    towards the panel, then centred within each comparable group.
            java.util.Arrays.fill(numS, 0);
            java.util.Arrays.fill(denS, 0);
            for (int i = 0; i < n; i++) {
                int g = o.grader()[i];
                numS[g] += o.w()[i] * (mean + a[o.student()[i]] + e[o.question()[i]] - o.y()[i]);
                denS[g] += o.w()[i];
            }
            double[] nextS = new double[s.length];
            for (int k = 0; k < s.length; k++) {
                nextS[k] = denS[k] == 0 ? 0 : numS[k] / (denS[k] + settings.graderShrinkage());
            }
            centreWithinGroups(nextS, groups);
            for (int k = 0; k < s.length; k++) {
                maxChange = Math.max(maxChange, Math.abs(nextS[k] - s[k]));
                s[k] = nextS[k];
            }

            // 4. The overall mean absorbs whatever the centring moved.
            double num = 0;
            for (int i = 0; i < n; i++) {
                num += o.w()[i] * (o.y()[i] - a[o.student()[i]] - e[o.question()[i]] + s[o.grader()[i]]);
            }
            double nextMean = num / totalW;
            maxChange = Math.max(maxChange, Math.abs(nextMean - mean));
            mean = nextMean;
        }

        Result partial = new Result(mean, a, e, s, 0, iteration, maxChange, maxChange <= settings.tolerance());
        double ss = 0;
        for (int i = 0; i < n; i++) {
            double r = o.y()[i] - partial.fitted(o, i);
            ss += o.w()[i] * r * r;
        }
        double sd = Math.sqrt(ss / totalW);
        return new Result(mean, a, e, s, sd, iteration, maxChange, partial.converged());
    }

    /** Severities average zero within each group, so each is read against its own panel. */
    static void centreWithinGroups(double[] severity, List<List<Integer>> groups) {
        for (List<Integer> group : groups) {
            double bar = 0;
            for (int g : group) bar += severity[g];
            bar /= group.size();
            for (int g : group) severity[g] -= bar;
        }
    }

    /** Root-mean-square severity: how much the assistants differ, in percentage points. */
    static double spread(double[] severity, List<List<Integer>> groups) {
        double ss = 0;
        int count = 0;
        for (List<Integer> group : groups) {
            if (group.size() < 2) continue;
            for (int g : group) {
                ss += severity[g] * severity[g];
                count++;
            }
        }
        return count == 0 ? 0 : Math.sqrt(ss / count);
    }
}
