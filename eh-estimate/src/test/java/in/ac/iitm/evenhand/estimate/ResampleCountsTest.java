package in.ac.iitm.evenhand.estimate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** The counts handed to other estimates are the very resamples the severity draws were fitted on. */
class ResampleCountsTest {

    @Test
    @DisplayName("each draw's counts are the students the bootstrap drew for that draw, and sum to the class size")
    void sameStudentsAsTheBootstrap() {
        int students = 37;
        // One row per student, so a resample's rows name its students directly.
        int[] s = new int[students], q = new int[students], g = new int[students];
        double[] y = new double[students], w = new double[students];
        for (int i = 0; i < students; i++) {
            s[i] = i;
            g[i] = i % 2;
            y[i] = i;
            w[i] = 1;
        }
        Observations obs = new Observations(s, q, g, y, w, students, 1, 2);
        int[][] rowsOf = new int[students][];
        for (int i = 0; i < students; i++) rowsOf[i] = new int[] {i};
        Settings settings = Settings.defaults().withDraws(25, 0);

        int[][] counts = SeverityEstimator.resampleCounts(students, settings);
        assertThat(counts).hasNumberOfRows(25);
        for (int b = 0; b < counts.length; b++) {
            Observations drawn = SeverityEstimator.resampleStudents(obs, rowsOf,
                    new SplittableRandom(settings.seed() * 1_000_003L + b));
            int[] seen = new int[students];
            for (double v : drawn.y()) seen[(int) v]++;
            assertThat(counts[b]).isEqualTo(seen);
            assertThat(java.util.Arrays.stream(counts[b]).sum()).isEqualTo(students);
        }
    }
}
