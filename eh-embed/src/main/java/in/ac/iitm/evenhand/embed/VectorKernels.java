package in.ac.iitm.evenhand.embed;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The linear layer with SIMD instructions through the JDK's Vector API: two tokens by four output
 * rows at a time, eight vector accumulators running along the input dimension with fused
 * multiply-adds. Loaded only when the {@code jdk.incubator.vector} module is present
 * ({@link Kernels#best}); never referenced directly, so its absence costs speed, not correctness.
 */
final class VectorKernels extends Kernels {

    private static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;

    @Override
    void linear(float[] in, int n, int inDim, float[] w, float[] b, int outDim, float[] out) {
        int lanes = S.length();
        int bound = S.loopBound(inDim);
        for (int o0 = 0; o0 < outDim; o0 += ROW_BLOCK) {
            int o1 = Math.min(outDim, o0 + ROW_BLOCK);
            int t = 0;
            for (; t + 1 < n; t += 2) {
                int a0 = t * inDim, a1 = a0 + inDim;
                int o = o0;
                for (; o + 3 < o1; o += 4) {
                    int w0 = o * inDim, w1 = w0 + inDim, w2 = w1 + inDim, w3 = w2 + inDim;
                    FloatVector z = FloatVector.zero(S);
                    FloatVector c00 = z, c01 = z, c02 = z, c03 = z, c10 = z, c11 = z, c12 = z, c13 = z;
                    for (int i = 0; i < bound; i += lanes) {
                        FloatVector x0 = FloatVector.fromArray(S, in, a0 + i);
                        FloatVector x1 = FloatVector.fromArray(S, in, a1 + i);
                        FloatVector v0 = FloatVector.fromArray(S, w, w0 + i);
                        FloatVector v1 = FloatVector.fromArray(S, w, w1 + i);
                        FloatVector v2 = FloatVector.fromArray(S, w, w2 + i);
                        FloatVector v3 = FloatVector.fromArray(S, w, w3 + i);
                        c00 = x0.fma(v0, c00);
                        c01 = x0.fma(v1, c01);
                        c02 = x0.fma(v2, c02);
                        c03 = x0.fma(v3, c03);
                        c10 = x1.fma(v0, c10);
                        c11 = x1.fma(v1, c11);
                        c12 = x1.fma(v2, c12);
                        c13 = x1.fma(v3, c13);
                    }
                    float s00 = c00.reduceLanes(VectorOperators.ADD), s01 = c01.reduceLanes(VectorOperators.ADD);
                    float s02 = c02.reduceLanes(VectorOperators.ADD), s03 = c03.reduceLanes(VectorOperators.ADD);
                    float s10 = c10.reduceLanes(VectorOperators.ADD), s11 = c11.reduceLanes(VectorOperators.ADD);
                    float s12 = c12.reduceLanes(VectorOperators.ADD), s13 = c13.reduceLanes(VectorOperators.ADD);
                    for (int i = bound; i < inDim; i++) {
                        s00 += in[a0 + i] * w[w0 + i];
                        s01 += in[a0 + i] * w[w1 + i];
                        s02 += in[a0 + i] * w[w2 + i];
                        s03 += in[a0 + i] * w[w3 + i];
                        s10 += in[a1 + i] * w[w0 + i];
                        s11 += in[a1 + i] * w[w1 + i];
                        s12 += in[a1 + i] * w[w2 + i];
                        s13 += in[a1 + i] * w[w3 + i];
                    }
                    int r0 = t * outDim, r1 = r0 + outDim;
                    out[r0 + o] = b[o] + s00;
                    out[r0 + o + 1] = b[o + 1] + s01;
                    out[r0 + o + 2] = b[o + 2] + s02;
                    out[r0 + o + 3] = b[o + 3] + s03;
                    out[r1 + o] = b[o] + s10;
                    out[r1 + o + 1] = b[o + 1] + s11;
                    out[r1 + o + 2] = b[o + 2] + s12;
                    out[r1 + o + 3] = b[o + 3] + s13;
                }
                for (; o < o1; o++) {
                    out[t * outDim + o] = b[o] + dot(in, a0, w, o * inDim, inDim, bound, lanes);
                    out[(t + 1) * outDim + o] = b[o] + dot(in, a1, w, o * inDim, inDim, bound, lanes);
                }
            }
            for (; t < n; t++) {
                for (int o = o0; o < o1; o++) {
                    out[t * outDim + o] = b[o] + dot(in, t * inDim, w, o * inDim, inDim, bound, lanes);
                }
            }
        }
    }

    private static float dot(float[] a, int ai, float[] w, int wi, int len, int bound, int lanes) {
        FloatVector acc = FloatVector.zero(S);
        for (int i = 0; i < bound; i += lanes) {
            acc = FloatVector.fromArray(S, a, ai + i).fma(FloatVector.fromArray(S, w, wi + i), acc);
        }
        float s = acc.reduceLanes(VectorOperators.ADD);
        for (int i = bound; i < len; i++) s += a[ai + i] * w[wi + i];
        return s;
    }

    @Override
    String name() {
        return "SIMD (" + S.vectorBitSize() + "-bit)";
    }
}
