package in.ac.iitm.evenhand.numerics;

/**
 * Eigenvalues and eigenvectors of a small dense symmetric matrix, by the cyclic
 * Jacobi rotation method.
 *
 * <p>Two jobs. It gives the numeric half of the audit's rank cross-check: the number
 * of eigenvalues of the Fisher information indistinguishable from zero is the null
 * dimension, and it must agree with what {@link ExactRowSpace} proves. And it gives
 * the generalised inverse used for standard errors, since the information matrix is
 * singular by construction &mdash; the parameters are fixed only up to the shifts
 * anchoring removes, so the honest inverse is the one taken on the identified
 * subspace and nowhere else.
 *
 * <p>Jacobi is chosen over anything faster because the matrices here are at most a
 * few hundred square, it is unconditionally stable for symmetric input, and its
 * eigenvalues come out accurate even when tiny &mdash; which is the regime the whole
 * audit lives in.
 */
public final class SymmetricEigen {

    private final double[] eigenvalues;
    private final double[][] eigenvectors; // column i is the vector for eigenvalues[i]

    private SymmetricEigen(double[] values, double[][] vectors) {
        this.eigenvalues = values;
        this.eigenvectors = vectors;
    }

    public static SymmetricEigen of(double[][] symmetric) {
        int n = symmetric.length;
        double[][] a = new double[n][n];
        for (int i = 0; i < n; i++) {
            if (symmetric[i].length != n) throw new IllegalArgumentException("matrix must be square");
            a[i] = symmetric[i].clone();
        }
        double[][] v = identity(n);

        for (int sweep = 0; sweep < 100; sweep++) {
            double off = offDiagonalNorm(a);
            if (off < 1e-14) break;
            for (int p = 0; p < n - 1; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    if (theta == 0.0) t = 1.0;
                    double c = 1.0 / Math.sqrt(t * t + 1.0);
                    double s = t * c;
                    rotate(a, v, p, q, c, s, n);
                }
            }
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = a[i][i];
        sortAscending(values, v, n);
        return new SymmetricEigen(values, v);
    }

    private static void rotate(double[][] a, double[][] v, int p, int q, double c, double s, int n) {
        double app = a[p][p], aqq = a[q][q], apq = a[p][q];
        a[p][p] = c * c * app - 2.0 * s * c * apq + s * s * aqq;
        a[q][q] = s * s * app + 2.0 * s * c * apq + c * c * aqq;
        a[p][q] = 0.0;
        a[q][p] = 0.0;
        for (int k = 0; k < n; k++) {
            if (k != p && k != q) {
                double akp = a[k][p], akq = a[k][q];
                a[k][p] = c * akp - s * akq;
                a[p][k] = a[k][p];
                a[k][q] = s * akp + c * akq;
                a[q][k] = a[k][q];
            }
            double vkp = v[k][p], vkq = v[k][q];
            v[k][p] = c * vkp - s * vkq;
            v[k][q] = s * vkp + c * vkq;
        }
    }

    private static double offDiagonalNorm(double[][] a) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            for (int j = i + 1; j < a.length; j++) sum += a[i][j] * a[i][j];
        }
        return Math.sqrt(2.0 * sum);
    }

    private static double[][] identity(int n) {
        double[][] v = new double[n][n];
        for (int i = 0; i < n; i++) v[i][i] = 1.0;
        return v;
    }

    private static void sortAscending(double[] values, double[][] vectors, int n) {
        for (int i = 0; i < n - 1; i++) {
            int min = i;
            for (int j = i + 1; j < n; j++) if (values[j] < values[min]) min = j;
            if (min != i) {
                double t = values[i]; values[i] = values[min]; values[min] = t;
                for (int k = 0; k < n; k++) {
                    double s = vectors[k][i]; vectors[k][i] = vectors[k][min]; vectors[k][min] = s;
                }
            }
        }
    }

    public double[] eigenvalues()   { return eigenvalues.clone(); }
    public int size()               { return eigenvalues.length; }

    /** The eigenvector for the {@code i}-th smallest eigenvalue. */
    public double[] eigenvector(int i) {
        double[] out = new double[eigenvalues.length];
        for (int k = 0; k < out.length; k++) out[k] = eigenvectors[k][i];
        return out;
    }

    /**
     * Number of eigenvalues small enough to be zero, relative to the largest.
     * A relative threshold is used because the information matrix is scaled by the
     * amount of data, so an absolute one would mean different things on different
     * courses.
     */
    public int numericalNullity(double relativeTolerance) {
        double largest = 0.0;
        for (double e : eigenvalues) largest = Math.max(largest, Math.abs(e));
        if (largest == 0.0) return eigenvalues.length;
        double cut = relativeTolerance * largest;
        int count = 0;
        for (double e : eigenvalues) if (Math.abs(e) <= cut) count++;
        return count;
    }

    /**
     * Moore-Penrose inverse, dropping directions whose eigenvalue is numerically
     * zero. Those directions are precisely the ones anchoring fixes, so discarding
     * them is the statement that we do not pretend to know what the data cannot say.
     */
    public double[][] pseudoInverse(double relativeTolerance) {
        int n = eigenvalues.length;
        double largest = 0.0;
        for (double e : eigenvalues) largest = Math.max(largest, Math.abs(e));
        double cut = relativeTolerance * largest;
        double[][] out = new double[n][n];
        for (int k = 0; k < n; k++) {
            if (Math.abs(eigenvalues[k]) <= cut) continue;
            double inv = 1.0 / eigenvalues[k];
            for (int i = 0; i < n; i++) {
                double vik = eigenvectors[i][k];
                if (vik == 0.0) continue;
                for (int j = 0; j < n; j++) {
                    out[i][j] += vik * inv * eigenvectors[j][k];
                }
            }
        }
        return out;
    }
}
