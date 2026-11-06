package in.ac.iitm.evenhand.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/** The piles' gap, recomputed on a given resample of students. */
class PileBalanceTest {

    /** Four students: two strong (+10) in TA 0's pile, two weak (-10) in TA 1's. Each wrote one answer. */
    private static PileBalance fourStudents() {
        PileBalance.Rows rows = new PileBalance.Rows(new int[] {0, 1, 2, 3}, new int[] {0, 0, 1, 1},
                new double[] {10, 10, -10, -10}, new double[] {1, 1, 1, 1}, 2);
        return new PileBalance(new double[] {10, -10}, 0.5, new double[0][], rows);
    }

    @Test
    @DisplayName("a resample that draws every student once gives back the gap itself")
    void everyoneOnceIsTheGap() {
        double[][] g = fourStudents().on(new int[][] {{1, 1, 1, 1}}).orElseThrow();
        assertThat(g[0][0]).isCloseTo(10, offset(1e-12));
        assertThat(g[0][1]).isCloseTo(-10, offset(1e-12));
    }

    @Test
    @DisplayName("a student drawn twice counts twice, and one not drawn does not count")
    void countsAreWeights() {
        // TA 0's pile: students 0 (x2) and 1 (x0), so its mean is still 10. Add a weaker answer to see the weighting.
        PileBalance.Rows rows = new PileBalance.Rows(new int[] {0, 1, 2, 3}, new int[] {0, 0, 1, 1},
                new double[] {10, 4, -10, -4}, new double[] {1, 1, 1, 1}, 2);
        PileBalance b = new PileBalance(new double[] {5, -5}, 0.5, new double[0][], rows);
        double[][] g = b.on(new int[][] {{2, 1, 1, 0}}).orElseThrow();
        // Pile 0: (2*10 + 4) / 3 = 8; pile 1: -10. Centred across the two TAs: +9 and -9.
        assertThat(g[0][0]).isCloseTo(9, offset(1e-12));
        assertThat(g[0][1]).isCloseTo(-9, offset(1e-12));
    }

    @Test
    @DisplayName("without the rows nothing is recomputed, and the caller keeps its own draws")
    void noRowsNoRecompute() {
        assertThat(new PileBalance(new double[] {1, -1}, 0.5, new double[0][]).on(new int[][] {{1}})).isEmpty();
    }
}
