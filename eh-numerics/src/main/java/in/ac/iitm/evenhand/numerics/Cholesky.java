package in.ac.iitm.evenhand.numerics;

/**
 * Solves {@code A x = b} for a symmetric positive-definite {@code A} by Cholesky
 * factorisation, {@code A = L L^T}.
 *
 * <p>The ridge regressions in the content analysis need one such solve per penalty and
 * fold, on matrices a few hundred square: Cholesky does it in a sixth of the work of a
 * general solver and fails loudly, rather than returning nonsense, if the matrix is not
 * positive definite.
 */
public final class Cholesky {

    private final double[][] l;

    private Cholesky(double[][] l) {
        this.l = l;
    }

    /**
     * Factorises {@code a} (only its lower triangle is read; it is not modified).
     *
     * @throws IllegalArgumentException if {@code a} is not square or not positive definite
     */
    public static Cholesky of(double[][] a) {
        int n = a.length;
        double[][] l = new double[n][];
        for (int i = 0; i < n; i++) {
            if (a[i].length != n) throw new IllegalArgumentException("matrix must be square");
            l[i] = new double[i + 1];
            for (int j = 0; j <= i; j++) {
                double s = a[i][j];
                double[] li = l[i], lj = l[j];
                for (int k = 0; k < j; k++) s -= li[k] * lj[k];
                if (i == j) {
                    if (!(s > 0)) throw new IllegalArgumentException("matrix is not positive definite (pivot " + s + ")");
                    li[i] = Math.sqrt(s);
                } else {
                    li[j] = s / lj[j];
                }
            }
        }
        return new Cholesky(l);
    }

    /** The solution of {@code A x = b}. */
    public double[] solve(double[] b) {
        int n = l.length;
        if (b.length != n) throw new IllegalArgumentException("right-hand side has the wrong length");
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double s = b[i];
            for (int k = 0; k < i; k++) s -= l[i][k] * y[k];
            y[i] = s / l[i][i];
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = y[i];
            for (int k = i + 1; k < n; k++) s -= l[k][i] * x[k];
            x[i] = s / l[i][i];
        }
        return x;
    }

    public int size() {
        return l.length;
    }
}
