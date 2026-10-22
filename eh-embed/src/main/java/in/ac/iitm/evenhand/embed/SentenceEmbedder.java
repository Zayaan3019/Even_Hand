package in.ac.iitm.evenhand.embed;

import java.util.List;

/**
 * Turns answers into unit vectors, so that the cosine of two answers (their dot product) says
 * how alike they are in content: near 1 for two answers that make the same points, lower for
 * answers that say different things.
 *
 * <p>Implementations must be deterministic, so a report reproduces exactly, and must return
 * one L2-normalised vector per text, in order.
 */
public interface SentenceEmbedder {

    /** What produced the vectors, for the report's footnotes: model name and revision. */
    String id();

    /** Whether this is a trained language model, or the lexical fallback. */
    boolean semantic();

    /** One unit vector per text, in the order given. */
    float[][] embed(List<String> texts);

    /** Cosine similarity of two unit vectors. */
    static double cosine(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    /** Scales a vector to unit length in place; a zero vector stays zero. */
    static float[] normalise(float[] v) {
        double ss = 0;
        for (float x : v) ss += x * x;
        if (ss == 0) return v;
        float inv = (float) (1.0 / Math.sqrt(ss));
        for (int i = 0; i < v.length; i++) v[i] *= inv;
        return v;
    }
}
