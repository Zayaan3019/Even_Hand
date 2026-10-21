package in.ac.iitm.evenhand.content;

import in.ac.iitm.evenhand.numerics.Cholesky;

/**
 * What the answers to one question predict about their marks, with the assistant held fixed.
 *
 * <pre>
 *   mark %  =  a[assistant]  +  beta . embedding  +  noise
 * </pre>
 *
 * <p>A ridge regression on the answer's embedding with one unpenalised intercept per
 * assistant. Because each assistant has their own intercept, {@code beta} is learned only from
 * how marks vary <em>within</em> each assistant's pile (Frisch-Waugh-Lovell), so it measures how
 * content drives the mark and cannot soak up an assistant's severity. In causal terms,
 * {@code beta . x} is a <em>prognostic score</em> (Hansen 2008): the mark an answer is expected to
 * earn from what it says.
 *
 * <p>Cross-fitted: each answer's prediction comes from a fit on the other folds, and folds are
 * whole students, so an answer marked twice never predicts itself. The penalty is chosen by
 * cross-validated error from a grid scaled to the data. Fitting in the dual (Gram) form when
 * there are fewer answers than dimensions keeps the lexical fallback's 4096 dimensions cheap.
 */
final class ContentModel {

    private static final double[] PENALTY_GRID = {0.001, 0.003, 0.01, 0.03, 0.1, 0.3, 1.0};

    /** Per answer, from the fold that held it out: prediction, content score, and that fold's beta. */
    final double[] predicted;
    final double[] contentScore;
    final double[][] beta;      // [fold][dim]
    final int[] foldOf;
    /** Cross-validated share of within-assistant variation the content explains (may be negative). */
    final double signal;
    final double penalty;

    private ContentModel(double[] predicted, double[] contentScore, double[][] beta, int[] foldOf,
                         double signal, double penalty) {
        this.predicted = predicted;
        this.contentScore = contentScore;
        this.beta = beta;
        this.foldOf = foldOf;
        this.signal = signal;
        this.penalty = penalty;
    }

    /** {@code beta . (x_i - x_j)} with the fold that held {@code i} out: the content difference in points. */
    double contentDifference(float[][] x, int i, int j) {
        double[] b = beta[foldOf[i]];
        double s = 0;
        float[] xi = x[i], xj = x[j];
        for (int c = 0; c < b.length; c++) s += b[c] * (xi[c] - xj[c]);
        return s;
    }

    /**
     * @param x       unit embeddings, one per answer
     * @param y       marks in percentage points of the question
     * @param grader  assistant of each answer, in {@code [0, graders)}
     * @param student student of each answer; folds are formed from students
     */
    static ContentModel fit(float[][] x, double[] y, int[] grader, int[] student, int graders, int folds, long seed) {
        int n = y.length;
        int d = n == 0 ? 0 : x[0].length;
        int[] foldOf = new int[n];
        for (int i = 0; i < n; i++) foldOf[i] = Math.floorMod(mix(student[i] * 0x9E3779B97F4A7C15L + seed), folds);

        double withinSs = withinSumOfSquares(y, grader, graders, null);
        double[][] predByPenalty = new double[PENALTY_GRID.length][n];
        double[][][] betaByPenalty = new double[PENALTY_GRID.length][folds][];
        for (int f = 0; f < folds; f++) {
            boolean[] train = new boolean[n];
            int trainCount = 0;
            for (int i = 0; i < n; i++) {
                train[i] = foldOf[i] != f;
                if (train[i]) trainCount++;
            }
            Fold fit = Fold.prepare(x, y, grader, graders, train, d);
            for (int p = 0; p < PENALTY_GRID.length; p++) {
                double[] b = trainCount < 3 ? new double[d] : fit.solve(PENALTY_GRID[p]);
                betaByPenalty[p][f] = b;
                for (int i = 0; i < n; i++) {
                    if (train[i]) continue;
                    predByPenalty[p][i] = fit.intercept(grader[i], b) + dot(b, x[i]);
                }
            }
        }
        int best = 0;
        double bestSse = Double.POSITIVE_INFINITY;
        for (int p = 0; p < PENALTY_GRID.length; p++) {
            double sse = 0;
            for (int i = 0; i < n; i++) sse += sq(y[i] - predByPenalty[p][i]);
            if (sse < bestSse - 1e-12) {
                bestSse = sse;
                best = p;
            }
        }
        double[][] beta = betaByPenalty[best];
        double[] score = new double[n];
        for (int i = 0; i < n; i++) score[i] = dot(beta[foldOf[i]], x[i]);
        double signal = withinSs <= 0 ? 0 : 1 - bestSse / withinSs;
        return new ContentModel(predByPenalty[best], score, beta, foldOf, signal, PENALTY_GRID[best]);
    }

    /** One training fold, centred within each assistant, ready to be solved for any penalty. */
    private static final class Fold {
        final double[][] xc;      // within-assistant-centred training rows
        final double[] yc;
        final double[][] meanX;   // per assistant
        final double[] meanY;
        final boolean[] seen;
        final double[] poolMeanX;
        final double poolMeanY;
        final double trace;
        final boolean dual;
        double[][] gram;          // dual: rows x rows; primal: d x d
        double[] rhs;             // primal only

        private Fold(double[][] xc, double[] yc, double[][] meanX, double[] meanY, boolean[] seen,
                     double[] poolMeanX, double poolMeanY, double trace, boolean dual) {
            this.xc = xc;
            this.yc = yc;
            this.meanX = meanX;
            this.meanY = meanY;
            this.seen = seen;
            this.poolMeanX = poolMeanX;
            this.poolMeanY = poolMeanY;
            this.trace = trace;
            this.dual = dual;
        }

        static Fold prepare(float[][] x, double[] y, int[] grader, int graders, boolean[] train, int d) {
            double[][] mx = new double[graders][d];
            double[] my = new double[graders];
            int[] cnt = new int[graders];
            double[] px = new double[d];
            double py = 0;
            int rows = 0;
            for (int i = 0; i < y.length; i++) {
                if (!train[i]) continue;
                int g = grader[i];
                cnt[g]++;
                my[g] += y[i];
                py += y[i];
                rows++;
                for (int c = 0; c < d; c++) {
                    mx[g][c] += x[i][c];
                    px[c] += x[i][c];
                }
            }
            boolean[] seen = new boolean[graders];
            for (int g = 0; g < graders; g++) {
                if (cnt[g] == 0) continue;
                seen[g] = true;
                my[g] /= cnt[g];
                for (int c = 0; c < d; c++) mx[g][c] /= cnt[g];
            }
            if (rows > 0) {
                py /= rows;
                for (int c = 0; c < d; c++) px[c] /= rows;
            }
            double[][] xc = new double[rows][d];
            double[] yc = new double[rows];
            double trace = 0;
            int r = 0;
            for (int i = 0; i < y.length; i++) {
                if (!train[i]) continue;
                int g = grader[i];
                yc[r] = y[i] - my[g];
                for (int c = 0; c < d; c++) {
                    double v = x[i][c] - mx[g][c];
                    xc[r][c] = v;
                    trace += v * v;
                }
                r++;
            }
            Fold f = new Fold(xc, yc, mx, my, seen, px, py, trace, rows < d);
            f.buildGram(d);
            return f;
        }

        private void buildGram(int d) {
            int rows = yc.length;
            if (dual) {
                gram = new double[rows][rows];
                for (int a = 0; a < rows; a++) {
                    for (int b = 0; b <= a; b++) {
                        double s = 0;
                        double[] xa = xc[a], xb = xc[b];
                        for (int c = 0; c < d; c++) s += xa[c] * xb[c];
                        gram[a][b] = s;
                        gram[b][a] = s;
                    }
                }
            } else {
                gram = new double[d][d];
                rhs = new double[d];
                for (int r = 0; r < rows; r++) {
                    double[] xr = xc[r];
                    for (int a = 0; a < d; a++) {
                        double va = xr[a];
                        if (va == 0) continue;
                        rhs[a] += va * yc[r];
                        double[] ga = gram[a];
                        for (int b = 0; b <= a; b++) ga[b] += va * xr[b];
                    }
                }
                for (int a = 0; a < d; a++) for (int b = 0; b < a; b++) gram[b][a] = gram[a][b];
            }
        }

        /** beta for penalty {@code c * trace}. */
        double[] solve(double c) {
            double lambda = Math.max(1e-9, c * trace);
            int m = gram.length;
            double[][] a = new double[m][];
            for (int i = 0; i < m; i++) {
                a[i] = gram[i].clone();
                a[i][i] += lambda;
            }
            Cholesky ch = Cholesky.of(a);
            if (!dual) return ch.solve(rhs);
            double[] alpha = ch.solve(yc);
            int d = xc.length == 0 ? 0 : xc[0].length;
            double[] b = new double[d];
            for (int r = 0; r < xc.length; r++) {
                double ar = alpha[r];
                double[] xr = xc[r];
                for (int k = 0; k < d; k++) b[k] += ar * xr[k];
            }
            return b;
        }

        /** The assistant's intercept, or the pooled one if this fold never saw them. */
        double intercept(int g, double[] b) {
            if (g < seen.length && seen[g]) return meanY[g] - dot(b, meanX[g]);
            return poolMeanY - dot(b, poolMeanX);
        }
    }

    static double withinSumOfSquares(double[] y, int[] grader, int graders, boolean[] use) {
        double[] sum = new double[graders];
        int[] cnt = new int[graders];
        for (int i = 0; i < y.length; i++) {
            if (use != null && !use[i]) continue;
            sum[grader[i]] += y[i];
            cnt[grader[i]]++;
        }
        double ss = 0;
        for (int i = 0; i < y.length; i++) {
            if (use != null && !use[i]) continue;
            ss += sq(y[i] - sum[grader[i]] / cnt[grader[i]]);
        }
        return ss;
    }

    private static double dot(double[] b, float[] x) {
        double s = 0;
        for (int c = 0; c < b.length; c++) s += b[c] * x[c];
        return s;
    }

    private static double dot(double[] b, double[] x) {
        double s = 0;
        for (int c = 0; c < b.length; c++) s += b[c] * x[c];
        return s;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (z ^ (z >>> 33));
    }
}
