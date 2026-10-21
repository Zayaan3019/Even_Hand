package in.ac.iitm.evenhand.embed;

/**
 * The one operation the encoder spends its time on: a linear layer over a batch of tokens,
 * {@code out[t][o] = b[o] + sum_i in[t][i] * W[o][i]} with {@code W} stored {@code [out][in]}, as
 * PyTorch stores it.
 *
 * <p>Two implementations compute the same thing. {@link VectorKernels} uses the JDK's Vector API
 * (SIMD, about five times faster on a laptop) and is used when the {@code jdk.incubator.vector}
 * module is present, which the launchers arrange; otherwise the plain-Java kernel here runs, so
 * the program works on any Java 21 installation, only slower.
 */
abstract class Kernels {

    /** Output rows handled together, so a block of the weight stays in cache for every token. */
    static final int ROW_BLOCK = 64;

    private static final Kernels BEST = choose();

    static Kernels best() {
        return BEST;
    }

    abstract void linear(float[] in, int n, int inDim, float[] w, float[] b, int outDim, float[] out);

    abstract String name();

    private static Kernels choose() {
        if ("scalar".equals(System.getProperty("evenhand.kernels"))) return new Scalar();
        boolean present = ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent();
        if (present) {
            try {
                return (Kernels) Class.forName("in.ac.iitm.evenhand.embed.VectorKernels").getDeclaredConstructor()
                        .newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                // fall through to the portable kernel
            }
        }
        return new Scalar();
    }

    /** Portable Java: eight partial sums per dot product, weight blocks reused across tokens. */
    static final class Scalar extends Kernels {
        @Override
        void linear(float[] in, int n, int inDim, float[] w, float[] b, int outDim, float[] out) {
            for (int o0 = 0; o0 < outDim; o0 += ROW_BLOCK) {
                int o1 = Math.min(outDim, o0 + ROW_BLOCK);
                for (int t = 0; t < n; t++) {
                    int ai = t * inDim, ot = t * outDim;
                    for (int o = o0; o < o1; o++) {
                        int bi = o * inDim;
                        float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0;
                        int i = 0;
                        for (; i + 7 < inDim; i += 8) {
                            s0 += in[ai + i] * w[bi + i];
                            s1 += in[ai + i + 1] * w[bi + i + 1];
                            s2 += in[ai + i + 2] * w[bi + i + 2];
                            s3 += in[ai + i + 3] * w[bi + i + 3];
                            s4 += in[ai + i + 4] * w[bi + i + 4];
                            s5 += in[ai + i + 5] * w[bi + i + 5];
                            s6 += in[ai + i + 6] * w[bi + i + 6];
                            s7 += in[ai + i + 7] * w[bi + i + 7];
                        }
                        for (; i < inDim; i++) s0 += in[ai + i] * w[bi + i];
                        out[ot + o] = b[o] + (((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7)));
                    }
                }
            }
        }

        @Override
        String name() {
            return "portable Java";
        }
    }
}
