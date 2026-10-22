package in.ac.iitm.evenhand.embed;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * The forward pass of a BERT-family encoder, in plain Java, for one sequence at a time.
 *
 * <pre>
 *   x = LayerNorm(word[id] + position[t] + type[0])
 *   repeat L times:
 *     a = softmax(Q K^T / sqrt(d)) V           (per head; Q, K, V are linear maps of x)
 *     x = LayerNorm(x + Linear(a))
 *     x = LayerNorm(x + Linear(GELU(Linear(x))))
 * </pre>
 *
 * <p>That is the whole of what a sentence-transformer computes before pooling. Inference needs
 * no autograd and no batching, so it fits in one class that can be read end to end and checked
 * number for number against the reference implementation (see {@code TransformerEmbedderTest}).
 * Weights are held as {@code float}, as they are stored; the linear layers, where the time goes, run
 * on SIMD instructions when the JDK's Vector API is available ({@link Kernels}).
 */
final class BertEncoder {

    final int hidden, heads, layers, intermediate, maxPositions;
    private final double layerNormEps;

    private final float[] wordEmb, posEmb, typeEmb, embLnW, embLnB;
    private final Layer[] layer;

    private record Layer(float[] q, float[] qb, float[] k, float[] kb, float[] v, float[] vb,
                         float[] ao, float[] aob, float[] ln1w, float[] ln1b,
                         float[] up, float[] upb, float[] down, float[] downb, float[] ln2w, float[] ln2b) {}

    private BertEncoder(Map<String, Object> config, SafeTensors t) {
        hidden = intOf(config, "hidden_size");
        heads = intOf(config, "num_attention_heads");
        layers = intOf(config, "num_hidden_layers");
        intermediate = intOf(config, "intermediate_size");
        maxPositions = intOf(config, "max_position_embeddings");
        layerNormEps = config.get("layer_norm_eps") instanceof Double d ? d : 1e-12;
        Object act = config.getOrDefault("hidden_act", "gelu");
        if (!"gelu".equals(act)) {
            throw new IllegalArgumentException("only GELU encoders are supported; this model uses " + act);
        }
        if (hidden % heads != 0) throw new IllegalArgumentException("hidden size is not a multiple of the heads");

        String p = t.has("embeddings.word_embeddings.weight") ? "" : "bert.";
        wordEmb = t.floats(p + "embeddings.word_embeddings.weight");
        posEmb = t.floats(p + "embeddings.position_embeddings.weight");
        typeEmb = t.floats(p + "embeddings.token_type_embeddings.weight");
        embLnW = t.floats(p + "embeddings.LayerNorm.weight");
        embLnB = t.floats(p + "embeddings.LayerNorm.bias");
        layer = new Layer[layers];
        for (int l = 0; l < layers; l++) {
            String b = p + "encoder.layer." + l + ".";
            layer[l] = new Layer(
                    t.floats(b + "attention.self.query.weight"), t.floats(b + "attention.self.query.bias"),
                    t.floats(b + "attention.self.key.weight"), t.floats(b + "attention.self.key.bias"),
                    t.floats(b + "attention.self.value.weight"), t.floats(b + "attention.self.value.bias"),
                    t.floats(b + "attention.output.dense.weight"), t.floats(b + "attention.output.dense.bias"),
                    t.floats(b + "attention.output.LayerNorm.weight"), t.floats(b + "attention.output.LayerNorm.bias"),
                    t.floats(b + "intermediate.dense.weight"), t.floats(b + "intermediate.dense.bias"),
                    t.floats(b + "output.dense.weight"), t.floats(b + "output.dense.bias"),
                    t.floats(b + "output.LayerNorm.weight"), t.floats(b + "output.LayerNorm.bias"));
        }
        int vocab = wordEmb.length / hidden;
        if (vocab * hidden != wordEmb.length || embLnW.length != hidden) {
            throw new IllegalArgumentException("the weights do not match config.json's hidden size " + hidden);
        }
    }

    static BertEncoder load(Path configJson, Path safetensors) throws IOException {
        Map<String, Object> config = Json.object(java.nio.file.Files.readString(configJson));
        return new BertEncoder(config, SafeTensors.open(safetensors));
    }

    int vocabSize() {
        return wordEmb.length / hidden;
    }

    /** The last layer's hidden states, {@code [token][hidden]} flattened, for the given ids. */
    float[] forward(int[] ids) {
        return forward(new int[][] {ids})[0];
    }

    /**
     * The last layer's hidden states for several sequences at once, each {@code [token][hidden]}
     * flattened. The sequences are packed end to end through the linear layers, which is where the
     * time goes, so each weight is read from memory once per batch rather than once per answer;
     * attention, positions and layer norms stay within each sequence, so every result is exactly
     * what that sequence alone would give.
     */
    float[][] forward(int[][] seqs) {
        int h = hidden, total = 0;
        int[] start = new int[seqs.length + 1];
        for (int s = 0; s < seqs.length; s++) {
            if (seqs[s].length > maxPositions) {
                throw new IllegalArgumentException("sequence longer than the model's " + maxPositions);
            }
            start[s] = total;
            total += seqs[s].length;
        }
        start[seqs.length] = total;
        int n = total;
        float[] x = new float[n * h];
        for (int s = 0; s < seqs.length; s++) {
            for (int p = 0; p < seqs[s].length; p++) {
                int id = seqs[s][p], t = start[s] + p;
                if (id < 0 || id >= vocabSize()) throw new IllegalArgumentException("token id " + id + " out of range");
                for (int c = 0; c < h; c++) x[t * h + c] = wordEmb[id * h + c] + posEmb[p * h + c] + typeEmb[c];
            }
        }
        layerNorm(x, n, embLnW, embLnB);

        int dHead = h / heads;
        float scale = (float) (1.0 / Math.sqrt(dHead));
        float[] q = new float[n * h], k = new float[n * h], v = new float[n * h], ctx = new float[n * h];
        float[] tmp = new float[n * h], up = new float[n * intermediate];
        int longest = 0;
        for (int[] seq : seqs) longest = Math.max(longest, seq.length);
        float[] scores = new float[longest];
        for (Layer L : layer) {
            linear(x, n, h, L.q(), L.qb(), h, q);
            linear(x, n, h, L.k(), L.kb(), h, k);
            linear(x, n, h, L.v(), L.vb(), h, v);
            for (int s = 0; s < seqs.length; s++) {
                int from = start[s], to = start[s + 1];
                for (int head = 0; head < heads; head++) {
                    int off = head * dHead;
                    for (int t = from; t < to; t++) {
                        float max = Float.NEGATIVE_INFINITY;
                        for (int u = from; u < to; u++) {
                            float sc = dot(q, t * h + off, k, u * h + off, dHead) * scale;
                            scores[u - from] = sc;
                            if (sc > max) max = sc;
                        }
                        double sum = 0;
                        for (int u = 0; u < to - from; u++) {
                            float e = (float) Math.exp(scores[u] - max);
                            scores[u] = e;
                            sum += e;
                        }
                        float inv = (float) (1.0 / sum);
                        int at = t * h + off;
                        for (int c = 0; c < dHead; c++) ctx[at + c] = 0f;
                        for (int u = from; u < to; u++) {
                            float pr = scores[u - from] * inv;
                            int src = u * h + off;
                            for (int c = 0; c < dHead; c++) ctx[at + c] += pr * v[src + c];
                        }
                    }
                }
            }
            linear(ctx, n, h, L.ao(), L.aob(), h, tmp);
            for (int i = 0; i < x.length; i++) x[i] += tmp[i];
            layerNorm(x, n, L.ln1w(), L.ln1b());

            linear(x, n, h, L.up(), L.upb(), intermediate, up);
            for (int i = 0; i < up.length; i++) up[i] = gelu(up[i]);
            linear(up, n, intermediate, L.down(), L.downb(), h, tmp);
            for (int i = 0; i < x.length; i++) x[i] += tmp[i];
            layerNorm(x, n, L.ln2w(), L.ln2b());
        }
        float[][] out = new float[seqs.length][];
        for (int s = 0; s < seqs.length; s++) out[s] = Arrays.copyOfRange(x, start[s] * h, start[s + 1] * h);
        return out;
    }

    /** {@code out[t][o] = b[o] + sum_i in[t][i] * W[o][i]}: PyTorch's Linear, by the fastest kernel available. */
    private static void linear(float[] in, int n, int inDim, float[] w, float[] b, int outDim, float[] out) {
        Kernels.best().linear(in, n, inDim, w, b, outDim, out);
    }

    /** Which linear-layer kernel is in use, for the report's footnote. */
    static String kernel() {
        return Kernels.best().name();
    }

    private static float dot(float[] a, int ai, float[] b, int bi, int len) {
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
        int i = 0;
        for (; i + 3 < len; i += 4) {
            s0 += a[ai + i] * b[bi + i];
            s1 += a[ai + i + 1] * b[bi + i + 1];
            s2 += a[ai + i + 2] * b[bi + i + 2];
            s3 += a[ai + i + 3] * b[bi + i + 3];
        }
        for (; i < len; i++) s0 += a[ai + i] * b[bi + i];
        return (s0 + s1) + (s2 + s3);
    }

    private void layerNorm(float[] x, int n, float[] w, float[] b) {
        int h = hidden;
        for (int t = 0; t < n; t++) {
            int at = t * h;
            double mean = 0;
            for (int c = 0; c < h; c++) mean += x[at + c];
            mean /= h;
            double var = 0;
            for (int c = 0; c < h; c++) {
                double d = x[at + c] - mean;
                var += d * d;
            }
            var /= h;
            double inv = 1.0 / Math.sqrt(var + layerNormEps);
            for (int c = 0; c < h; c++) x[at + c] = (float) ((x[at + c] - mean) * inv * w[c] + b[c]);
        }
    }

    /** The exact GELU BERT was trained with: x * Phi(x), not the tanh approximation. */
    static float gelu(float x) {
        return (float) (0.5 * x * (1.0 + erfFast(x * 0.7071067811865476)));
    }

    /**
     * erf to 1.2e-7 everywhere (Numerical Recipes' erfcc, a Chebyshev fit), about ten times faster
     * than {@link #erf}. GELU is evaluated half a million times per answer, and 1.2e-7 is far
     * below float precision in the activations it feeds.
     */
    static double erfFast(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? 1.0 - r : r - 1.0;
    }

    /**
     * The error function to about 1e-15 relative error: a Taylor series near zero and a
     * continued fraction for the tail (Numerical Recipes, 3rd ed., section 6.2).
     */
    static double erf(double x) {
        double ax = Math.abs(x);
        if (ax < 2.5) {
            // erf(x) = 2/sqrt(pi) * sum_n (-1)^n x^(2n+1) / (n! (2n+1))
            double term = ax, sum = ax, x2 = ax * ax;
            for (int n = 1; n < 60; n++) {
                term *= -x2 / n;
                double add = term / (2 * n + 1);
                sum += add;
                if (Math.abs(add) < 1e-17 * Math.abs(sum)) break;
            }
            double r = 2.0 / Math.sqrt(Math.PI) * sum;
            return x < 0 ? -r : r;
        }
        if (ax > 6.0) return x < 0 ? -1.0 : 1.0;
        // erfc(x) = exp(-x^2)/sqrt(pi) * 1/(x + 1/2/(x + 1/(x + 3/2/(x + ...)))), by Lentz's method
        double tiny = 1e-300, f = ax, c = ax, d = 0;
        for (int i = 1; i < 300; i++) {
            double a = i / 2.0;
            d = ax + a * d;
            d = Math.abs(d) < tiny ? tiny : d;
            c = ax + a / c;
            c = Math.abs(c) < tiny ? tiny : c;
            d = 1.0 / d;
            double delta = c * d;
            f *= delta;
            if (Math.abs(delta - 1.0) < 1e-16) break;
        }
        double erfc = Math.exp(-ax * ax) / Math.sqrt(Math.PI) / f;
        return x < 0 ? erfc - 1.0 : 1.0 - erfc;
    }

    private static int intOf(Map<String, Object> config, String key) {
        Object v = config.get(key);
        if (!(v instanceof Double d)) throw new IllegalArgumentException("config.json has no " + key);
        return d.intValue();
    }
}
