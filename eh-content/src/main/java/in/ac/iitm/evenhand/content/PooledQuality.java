package in.ac.iitm.evenhand.content;

import in.ac.iitm.evenhand.numerics.Cholesky;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A quality model shared by every question, so that a question with thirty answers still gets a
 * usable estimate of what each answer deserves.
 *
 * <pre>
 *   mark %  =  cell[question, assistant]  +  theta . (similarity to the answer key,
 *                                                     similarity to the class's consensus answer)  +  ...
 * </pre>
 *
 * <p>Two features that short-answer grading research has long found predictive, each meaningful on
 * any question: how close the answer is to the instructor's key (when one is given), and how close
 * it is to what the rest of the class wrote (the consensus, leave-one-out, so an answer is never
 * compared with itself). On real double-graded answers (UNT, 2,273 answers) their within-question
 * correlations with the graders' average mark are 0.53 and 0.54. Answer length (0.21) is
 * deliberately left out: it is kept as a negative control, which only works if nothing adjusts for it. The weights {@code theta} are learned from variation <em>within</em> each (question, assistant)
 * cell, so they cannot absorb severity, and cross-fitted on the same student folds as the
 * per-question model, which then learns only what the embedding adds.
 */
final class PooledQuality {

    static final int FEATURES = 2;

    private PooledQuality() {
    }

    /** Per question, each answer's cross-fitted pooled prediction (an offset for {@link ContentModel}). */
    static Map<Matching, double[]> offsets(List<Matching> questions, Map<Integer, float[]> references,
                                           int folds, long seed) {
        Map<Matching, double[][]> features = new LinkedHashMap<>();
        for (Matching q : questions) features.put(q, features(q, references.get(q.question)));

        double[][] theta = new double[folds][];
        for (int f = 0; f < folds; f++) theta[f] = fitFold(questions, features, f, folds, seed);

        Map<Matching, double[]> out = new LinkedHashMap<>();
        for (Matching q : questions) {
            double[][] x = features.get(q);
            double[] o = new double[q.size()];
            for (int i = 0; i < q.size(); i++) {
                double[] t = theta[ContentModel.fold(q.student[i], folds, seed)];
                for (int k = 0; k < FEATURES; k++) o[i] += t[k] * x[i][k];
            }
            out.put(q, o);
        }
        return out;
    }

    /** The three features of each answer to one question. */
    static double[][] features(Matching q, float[] reference) {
        int n = q.size(), d = q.x[0].length;
        double[] sum = new double[d];
        for (float[] v : q.x) for (int c = 0; c < d; c++) sum[c] += v[c];
        double[][] out = new double[n][FEATURES];
        for (int i = 0; i < n; i++) {
            double dot = 0, norm = 0;
            for (int c = 0; c < d; c++) {
                double rest = sum[c] - q.x[i][c];
                dot += q.x[i][c] * rest;
                norm += rest * rest;
            }
            out[i][0] = norm > 0 ? dot / Math.sqrt(norm) : 0;
            out[i][1] = reference == null ? 0 : Matching.dot(q.x[i], reference);
        }
        return out;
    }

    /** theta from the training folds, with features and marks centred within each (question, assistant) cell. */
    private static double[] fitFold(List<Matching> questions, Map<Matching, double[][]> features, int heldOut,
                                    int folds, long seed) {
        double[][] xtx = new double[FEATURES][FEATURES];
        double[] xty = new double[FEATURES];
        for (Matching q : questions) {
            double[][] x = features.get(q);
            Map<Integer, double[]> cell = new LinkedHashMap<>();   // grader -> sums of y, features, count
            for (int i = 0; i < q.size(); i++) {
                if (ContentModel.fold(q.student[i], folds, seed) == heldOut) continue;
                double[] c = cell.computeIfAbsent(q.grader[i], g -> new double[FEATURES + 2]);
                c[0] += q.y[i];
                for (int k = 0; k < FEATURES; k++) c[1 + k] += x[i][k];
                c[FEATURES + 1]++;
            }
            for (int i = 0; i < q.size(); i++) {
                if (ContentModel.fold(q.student[i], folds, seed) == heldOut) continue;
                double[] c = cell.get(q.grader[i]);
                double m = c[FEATURES + 1];
                if (m < 2) continue;
                double yc = q.y[i] - c[0] / m;
                double[] xc = new double[FEATURES];
                for (int k = 0; k < FEATURES; k++) xc[k] = x[i][k] - c[1 + k] / m;
                for (int a = 0; a < FEATURES; a++) {
                    xty[a] += xc[a] * yc;
                    for (int b = 0; b < FEATURES; b++) xtx[a][b] += xc[a] * xc[b];
                }
            }
        }
        double trace = 0;
        for (int k = 0; k < FEATURES; k++) trace += xtx[k][k];
        for (int k = 0; k < FEATURES; k++) {
            // A feature that never varies (no answer keys given) is pinned at zero by the ridge.
            xtx[k][k] += Math.max(1e-9, 1e-4 * trace / FEATURES);
        }
        return Cholesky.of(xtx).solve(xty);
    }
}
