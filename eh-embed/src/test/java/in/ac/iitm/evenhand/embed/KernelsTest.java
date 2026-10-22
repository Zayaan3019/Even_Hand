package in.ac.iitm.evenhand.embed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class KernelsTest {

    @Test
    @DisplayName("the SIMD and portable kernels compute the same linear layer, odd sizes included")
    void kernelsAgree() {
        Random rng = new Random(5);
        int[][] shapes = { {1, 384, 384}, {7, 384, 1536}, {57, 1536, 384}, {3, 13, 70} };
        for (int[] shape : shapes) {
            int n = shape[0], in = shape[1], out = shape[2];
            float[] x = new float[n * in], w = new float[out * in], b = new float[out];
            for (int i = 0; i < x.length; i++) x[i] = (float) rng.nextGaussian();
            for (int i = 0; i < w.length; i++) w[i] = (float) (rng.nextGaussian() / Math.sqrt(in));
            for (int i = 0; i < b.length; i++) b[i] = (float) rng.nextGaussian();
            float[] a = new float[n * out], c = new float[n * out];
            new Kernels.Scalar().linear(x, n, in, w, b, out, a);
            Kernels.best().linear(x, n, in, w, b, out, c);
            for (int t = 0; t < n; t++) {
                for (int o = 0; o < out; o++) {
                    double expect = b[o];
                    for (int i = 0; i < in; i++) expect += (double) x[t * in + i] * w[o * in + i];
                    assertThat((double) a[t * out + o]).isCloseTo(expect, within(1e-3));
                    assertThat((double) c[t * out + o]).isCloseTo(expect, within(1e-3));
                }
            }
        }
    }

    @Test
    @DisplayName("the build runs the SIMD kernel, as the launchers do")
    void simdInUse() {
        assertThat(Kernels.best().name()).as(Kernels.fallbackReason()).startsWith("SIMD");
    }

    @Test
    @DisplayName("the fast erf behind GELU stays within 2e-7 of the exact one")
    void fastErf() {
        for (double x = -6; x <= 6; x += 0.01) {
            assertThat(BertEncoder.erfFast(x)).isCloseTo(BertEncoder.erf(x), within(2e-7));
        }
    }
}
