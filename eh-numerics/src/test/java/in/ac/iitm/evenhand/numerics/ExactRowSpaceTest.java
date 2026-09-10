package in.ac.iitm.evenhand.numerics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact half of the audit's arithmetic.
 *
 * <p>Everything the platform refuses to do rests on a rank. If the rank is wrong, the
 * audit either refuses a course it could have measured or, far worse, measures one it
 * should have refused. These tests pin it against matrices whose rank is known by
 * hand, and against the one case where floating point would give a different answer.
 */
class ExactRowSpaceTest {

    @Test
    @DisplayName("rank and nullity match hand-computed values")
    void rankMatchesHandComputation() {
        ExactRowSpace space = new ExactRowSpace(3);

        assertThat(space.addRow(new int[] {1, 0, 0})).isTrue();
        assertThat(space.addRow(new int[] {0, 1, 0})).isTrue();
        assertThat(space.addRow(new int[] {1, 1, 0})).isFalse();  // dependent
        assertThat(space.rank()).isEqualTo(2);
        assertThat(space.nullity()).isEqualTo(1);
    }

    @Test
    @DisplayName("a repeated row never raises the rank, however often it arrives")
    void duplicateRowsDoNotInflateRank() {
        ExactRowSpace space = new ExactRowSpace(4);
        for (int i = 0; i < 500; i++) {
            space.addRow(new int[] {1, -1, -1, 0});
        }
        // Every student marked on the same question by the same assistant adds no
        // information about that assistant, and the rank has to say so no matter how
        // many rows arrive.
        assertThat(space.rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("membership is exact where a tolerance would have to guess")
    void containsIsExactOnNearlyDependentRows() {
        ExactRowSpace space = new ExactRowSpace(3);
        space.addRow(new int[] {1_000_000, 1, 0});
        space.addRow(new int[] {1_000_001, 1, 0});

        // The two rows differ by (1, 0, 0), so that vector is in the span and (0, 0, 1)
        // is not. In double precision the first pair is close enough to parallel that a
        // rank tolerance decides the answer; over the integers there is nothing to
        // decide. This is why the verdict is computed this way.
        assertThat(space.rank()).isEqualTo(2);
        assertThat(space.contains(new int[] {1, 0, 0})).isTrue();
        assertThat(space.contains(new int[] {0, 0, 1})).isFalse();
    }

    @Test
    @DisplayName("the null space is a genuine null space: every basis vector is annihilated")
    void nullSpaceVectorsAreAnnihilatedByEveryRow() {
        int[][] rows = {
                {1, -1, -1, 0, 0},
                {1, 0, -1, -1, 0},
                {0, 1, 0, -1, 0},
        };
        ExactRowSpace space = new ExactRowSpace(5);
        for (int[] r : rows) {
            space.addRow(r);
        }

        var basis = space.nullSpace();

        assertThat(basis).hasSize(space.nullity());
        for (double[] v : basis) {
            for (int[] r : rows) {
                double dot = 0.0;
                for (int j = 0; j < 5; j++) {
                    dot += r[j] * v[j];
                }
                // A null direction is one that changes no prediction anywhere. If any row
                // saw it, the report would be naming parameters as inseparable that the
                // marking actually separates.
                assertThat(dot).isCloseTo(0.0, org.assertj.core.api.Assertions.within(1e-9));
            }
        }
    }

    @Test
    @DisplayName("exact rank agrees with the eigenvalue count on the same matrix")
    void exactAndNumericRanksAgree() {
        int[][] rows = {
                {1, -1, -1, 0},
                {1, -1, 0, -1},
                {1, 0, -1, -1},
        };
        ExactRowSpace space = new ExactRowSpace(4);
        for (int[] r : rows) {
            space.addRow(r);
        }

        // The audit computes its answer twice by different routes and requires agreement.
        // Here that cross-check is exercised directly: the Gram matrix of the same rows
        // must have exactly as many non-zero eigenvalues as the integer elimination found
        // independent rows.
        double[][] gram = new double[4][4];
        for (int[] r : rows) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    gram[i][j] += (double) r[i] * r[j];
                }
            }
        }
        SymmetricEigen eigen = SymmetricEigen.of(gram);

        assertThat(4 - eigen.numericalNullity(1e-9)).isEqualTo(space.rank());
    }
}
