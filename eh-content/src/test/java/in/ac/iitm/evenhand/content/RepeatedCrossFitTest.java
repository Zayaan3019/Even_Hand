package in.ac.iitm.evenhand.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** No single random split of the students should decide a finding. */
class RepeatedCrossFitTest {

    private static final int N = 60, D = 8;

    private static float[][] vectors() {
        Random r = new Random(11);
        float[][] x = new float[N][D];
        for (float[] v : x) for (int c = 0; c < D; c++) v[c] = (float) r.nextGaussian();
        return x;
    }

    private static double[] marks(float[][] x) {
        Random r = new Random(12);
        double[] y = new double[N];
        for (int i = 0; i < N; i++) y[i] = 50 + 8 * x[i][0] - 5 * x[i][1] + 4 * r.nextGaussian();
        return y;
    }

    private static ContentModel fit(float[][] x, double[] y, long seed) {
        int[] grader = new int[N], student = new int[N];
        for (int i = 0; i < N; i++) {
            grader[i] = i % 2;
            student[i] = i;
        }
        return ContentModel.fit(x, y, grader, student, 2, 5, seed);
    }

    @Test
    @DisplayName("the average of the fits predicts, and compares answers, as the mean of the fits does")
    void averageIsTheMean() {
        float[][] x = vectors();
        double[] y = marks(x);
        ContentModel a = fit(x, y, 1), b = fit(x, y, 2);

        ContentModel mean = ContentModel.average(new ContentModel[] {a, b});

        assertThat(mean.predicted[7]).isCloseTo((a.predicted[7] + b.predicted[7]) / 2, within(1e-9));
        assertThat(mean.contentDifference(x, 3, 9))
                .isCloseTo((a.contentDifference(x, 3, 9) + b.contentDifference(x, 3, 9)) / 2, within(1e-9));
        assertThat(mean.signal).isCloseTo(1 - (a.sse + b.sse) / 2 / a.withinSs, within(1e-9));
    }

    @Test
    @DisplayName("a pair's content difference is the fold's coefficients applied to the two answers")
    void differenceMatchesTheCoefficients() {
        float[][] x = vectors();
        ContentModel m = fit(x, marks(x), 3);

        double[] b = m.beta[m.foldOf[4]];
        double expected = 0;
        for (int c = 0; c < D; c++) expected += b[c] * (x[4][c] - x[5][c]);

        assertThat(m.contentDifference(x, 4, 5)).isCloseTo(expected, within(1e-5));
    }

    @Test
    @DisplayName("averaging leaves less of the split's luck in a comparison")
    void averagingSteadiesTheComparison() {
        float[][] x = vectors();
        double[] y = marks(x);
        double spreadOne = 0, spreadFive = 0;
        for (int pair = 0; pair < 20; pair++) {
            double[] one = new double[8], five = new double[8];
            for (int s = 0; s < 8; s++) {
                one[s] = fit(x, y, 100 + s).contentDifference(x, pair, pair + 20);
                ContentModel[] fits = new ContentModel[5];
                for (int r = 0; r < 5; r++) fits[r] = fit(x, y, 1000 + 10L * s + r);
                five[s] = ContentModel.average(fits).contentDifference(x, pair, pair + 20);
            }
            spreadOne += range(one);
            spreadFive += range(five);
        }
        assertThat(spreadFive).isLessThan(spreadOne);
    }

    private static double range(double[] v) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double d : v) {
            lo = Math.min(lo, d);
            hi = Math.max(hi, d);
        }
        return hi - lo;
    }
}
