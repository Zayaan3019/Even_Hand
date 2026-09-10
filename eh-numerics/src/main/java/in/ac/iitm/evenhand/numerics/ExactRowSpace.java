package in.ac.iitm.evenhand.numerics;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Row space of an integer matrix, maintained exactly, one row at a time.
 *
 * <p>The identifiability audit needs a rank. Computing it in floating point means
 * choosing a tolerance, and the answer near that tolerance is the answer to a
 * question about rounding rather than about the marking. Since the design matrix has
 * entries in {-1, 0, 1}, we can avoid the choice entirely: this class does
 * fraction-free elimination over the integers, so the rank it reports is a
 * mathematical fact about the arrangement, not an artefact of arithmetic.
 *
 * <p>Rows are reduced against the accumulated echelon basis as they arrive and kept
 * only if they are independent, so memory is bounded by the rank rather than by the
 * number of marked responses. Each surviving row is divided through by the gcd of
 * its entries, which is what stops fraction-free elimination from growing
 * coefficients.
 *
 * <p>This is the exact half of the audit's cross-check; {@link SymmetricEigen}
 * provides the numeric half, and the two must agree on every scenario fixture. That
 * matters because the established tool in this area, Facets, uses a graph-joining
 * heuristic whose manual states outright that "there are exotic forms of
 * connectedness which Facets may falsely report as disconnected".
 */
public final class ExactRowSpace {

    private final int columns;
    /** Echelon rows, each with a distinct leading column. */
    private final List<BigInteger[]> rows = new ArrayList<>();
    /** pivotColumn[i] is the leading column of rows.get(i); kept ascending. */
    private final List<Integer> pivotColumns = new ArrayList<>();

    public ExactRowSpace(int columns) {
        if (columns <= 0) throw new IllegalArgumentException("columns must be positive");
        this.columns = columns;
    }

    public int columns() { return columns; }

    public int rank() { return rows.size(); }

    /** Columns that carry a pivot, ascending. */
    public List<Integer> pivotColumns() { return List.copyOf(pivotColumns); }

    /**
     * Reduces {@code row} against the current basis and keeps it if independent.
     *
     * @return true when the rank increased
     */
    public boolean addRow(int[] row) {
        if (row.length != columns) {
            throw new IllegalArgumentException("row has " + row.length + " entries, expected " + columns);
        }
        BigInteger[] r = new BigInteger[columns];
        for (int i = 0; i < columns; i++) r[i] = BigInteger.valueOf(row[i]);
        return addRow(r);
    }

    private boolean addRow(BigInteger[] r) {
        for (int i = 0; i < rows.size(); i++) {
            int c = pivotColumns.get(i);
            if (r[c].signum() != 0) {
                // r := r * pivot[c] - pivotRow * r[c]  -- stays integral, clears column c
                BigInteger[] p = rows.get(i);
                BigInteger a = p[c];
                BigInteger b = r[c];
                for (int j = 0; j < columns; j++) {
                    r[j] = r[j].multiply(a).subtract(p[j].multiply(b));
                }
                normalise(r);
            }
        }
        int lead = leadingColumn(r);
        if (lead < 0) {
            return false; // dependent on what we already have
        }
        normalise(r);
        int insertAt = 0;
        while (insertAt < pivotColumns.size() && pivotColumns.get(insertAt) < lead) insertAt++;
        rows.add(insertAt, r);
        pivotColumns.add(insertAt, lead);
        return true;
    }

    private static int leadingColumn(BigInteger[] r) {
        for (int j = 0; j < r.length; j++) {
            if (r[j].signum() != 0) return j;
        }
        return -1;
    }

    /** Divide through by the gcd, and make the leading entry positive. */
    private static void normalise(BigInteger[] r) {
        BigInteger g = BigInteger.ZERO;
        for (BigInteger v : r) {
            if (v.signum() != 0) g = g.gcd(v);
        }
        if (g.signum() == 0) return;
        int lead = leadingColumn(r);
        boolean flip = lead >= 0 && r[lead].signum() < 0;
        for (int j = 0; j < r.length; j++) {
            r[j] = r[j].divide(g);
            if (flip) r[j] = r[j].negate();
        }
    }

    /**
     * A basis for the null space {@code {x : Ax = 0}}, one vector per free column.
     *
     * <p>Each vector says which parameters move together without changing any
     * prediction, which is exactly what the report must explain to the instructor:
     * "this assistant's severity and this question's difficulty are only ever seen
     * added together, so no amount of data separates them."
     */
    public List<double[]> nullSpace() {
        // Reduce to row-reduced echelon form over the rationals, in doubles: the
        // pivot structure is already known exactly, so no tolerance decision is made
        // here -- only the back-substitution arithmetic is floating point.
        int r = rows.size();
        double[][] m = new double[r][columns];
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < columns; j++) {
                m[i][j] = rows.get(i)[j].doubleValue();
            }
        }
        for (int i = r - 1; i >= 0; i--) {
            int c = pivotColumns.get(i);
            double piv = m[i][c];
            for (int j = 0; j < columns; j++) m[i][j] /= piv;
            for (int k = 0; k < i; k++) {
                double f = m[k][c];
                if (f != 0.0) {
                    for (int j = 0; j < columns; j++) m[k][j] -= f * m[i][j];
                }
            }
        }
        boolean[] isPivot = new boolean[columns];
        for (int c : pivotColumns) isPivot[c] = true;

        List<double[]> basis = new ArrayList<>();
        for (int free = 0; free < columns; free++) {
            if (isPivot[free]) continue;
            double[] v = new double[columns];
            v[free] = 1.0;
            for (int i = 0; i < r; i++) {
                v[pivotColumns.get(i)] = -m[i][free];
            }
            basis.add(v);
        }
        return basis;
    }


    /**
     * Whether {@code row} already lies in the row space, without changing it.
     *
     * <p>This is the exact test for whether a contrast is estimable. A difference of
     * two severities can be estimated precisely when the vector expressing it is a
     * combination of rows the marking actually produced; if it is not, no amount of
     * data from this arrangement will separate those two graders. Asking the question
     * this way gives a yes or no with no tolerance in it.
     */
    public boolean contains(int[] row) {
        if (row.length != columns) {
            throw new IllegalArgumentException("row has " + row.length + " entries, expected " + columns);
        }
        BigInteger[] r = new BigInteger[columns];
        for (int i = 0; i < columns; i++) r[i] = BigInteger.valueOf(row[i]);
        for (int i = 0; i < rows.size(); i++) {
            int c = pivotColumns.get(i);
            if (r[c].signum() != 0) {
                BigInteger[] p = rows.get(i);
                BigInteger a = p[c];
                BigInteger b = r[c];
                for (int j = 0; j < columns; j++) {
                    r[j] = r[j].multiply(a).subtract(p[j].multiply(b));
                }
                normalise(r);
            }
        }
        return leadingColumn(r) < 0;
    }

    /** Dimension of the null space: {@code columns - rank}. */
    public int nullity() {
        return columns - rank();
    }
}
