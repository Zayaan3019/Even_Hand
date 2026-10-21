package in.ac.iitm.evenhand.numerics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CholeskyTest {

    @Test
    @DisplayName("solves a random positive-definite system to 1e-9")
    void solvesRandomSystems() {
        Random rng = new Random(3);
        for (int n : new int[] {1, 2, 7, 60}) {
            double[][] m = new double[n][n];
            for (double[] row : m) for (int j = 0; j < n; j++) row[j] = rng.nextGaussian();
            double[][] a = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    for (int k = 0; k < n; k++) a[i][j] += m[k][i] * m[k][j];
                }
                a[i][i] += 0.5;
            }
            double[] x = new double[n];
            for (int i = 0; i < n; i++) x[i] = rng.nextGaussian();
            double[] b = new double[n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) b[i] += a[i][j] * x[j];
            double[] got = Cholesky.of(a).solve(b);
            for (int i = 0; i < n; i++) assertThat(got[i]).isCloseTo(x[i], within(1e-9));
        }
    }

    @Test
    @DisplayName("refuses a matrix that is not positive definite")
    void refusesIndefinite() {
        assertThatThrownBy(() -> Cholesky.of(new double[][] {{1, 2}, {2, 1}}))
                .hasMessageContaining("not positive definite");
    }
}
