package in.ac.iitm.evenhand.numerics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The eigen-decomposition behind the standard errors.
 *
 * <p>Its job is not only to be accurate but to be accurate about the directions that
 * are nearly absent, because those are exactly the ones anchoring removes. A routine
 * that got the large eigenvalues right and the tiny ones wrong would produce
 * confident-looking intervals for parameters the marking never pinned down.
 */
class SymmetricEigenTest {

    @Test
    @DisplayName("eigenvalues of a matrix with known spectrum, in ascending order")
    void knownSpectrum() {
        // [[2,1],[1,2]] has eigenvalues 1 and 3.
        SymmetricEigen eigen = SymmetricEigen.of(new double[][] {{2, 1}, {1, 2}});

        assertThat(eigen.eigenvalues()[0]).isCloseTo(1.0, within(1e-10));
        assertThat(eigen.eigenvalues()[1]).isCloseTo(3.0, within(1e-10));
    }

    @Test
    @DisplayName("eigenvectors are orthonormal and actually satisfy Av = lambda v")
    void eigenvectorsSatisfyTheirDefinition() {
        double[][] a = {{4, 1, 0}, {1, 3, 1}, {0, 1, 2}};
        SymmetricEigen eigen = SymmetricEigen.of(a);

        for (int k = 0; k < 3; k++) {
            double[] v = eigen.eigenvector(k);
            double lambda = eigen.eigenvalues()[k];

            double norm = 0.0;
            for (double x : v) {
                norm += x * x;
            }
            assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-9));

            for (int i = 0; i < 3; i++) {
                double av = 0.0;
                for (int j = 0; j < 3; j++) {
                    av += a[i][j] * v[j];
                }
                assertThat(av).isCloseTo(lambda * v[i], within(1e-8));
            }
        }
    }

    @Test
    @DisplayName("a singular direction is found, and the pseudo-inverse leaves it alone")
    void pseudoInverseIgnoresTheUnidentifiedDirection() {
        // Rank 1: the direction (1,-1)/sqrt(2) carries no information at all. This is the
        // shape the Fisher information always has here, because the parameters are fixed
        // only up to the shifts anchoring removes.
        double[][] singular = {{1, 1}, {1, 1}};
        SymmetricEigen eigen = SymmetricEigen.of(singular);

        assertThat(eigen.numericalNullity(1e-9)).isEqualTo(1);

        double[][] inverse = eigen.pseudoInverse(1e-9);

        // Inverting on the identified subspace only: A * A+ * A must return A, while the
        // null direction stays null rather than being handed an enormous variance.
        double[][] roundTrip = new double[2][2];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 2; k++) {
                    for (int l = 0; l < 2; l++) {
                        roundTrip[i][j] += singular[i][k] * inverse[k][l] * singular[l][j];
                    }
                }
            }
        }
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                assertThat(roundTrip[i][j]).isCloseTo(singular[i][j], within(1e-8));
            }
        }
    }

    @Test
    @DisplayName("nullity is judged relative to scale, so it means the same on any class size")
    void nullityIsScaleInvariant() {
        double[][] small = {{1, 1}, {1, 1}};
        double[][] large = {{1e6, 1e6}, {1e6, 1e6}};

        // The information matrix grows with the amount of data. An absolute threshold
        // would call a large course's genuine null direction "non-zero" and a small
        // course's real information "zero", which would make the verdict depend on class
        // size rather than on the marking.
        assertThat(SymmetricEigen.of(small).numericalNullity(1e-9))
                .isEqualTo(SymmetricEigen.of(large).numericalNullity(1e-9))
                .isEqualTo(1);
    }
}
