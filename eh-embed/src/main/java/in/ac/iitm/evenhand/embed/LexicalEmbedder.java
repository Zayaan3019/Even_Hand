package in.ac.iitm.evenhand.embed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fallback when no language model is installed: TF-IDF over words and word pairs, hashed
 * into a fixed number of dimensions and scaled to unit length.
 *
 * <p>It knows nothing about meaning: "tensile" and "in tension" are unrelated to it. It does
 * know when two answers use the same words, which is enough to find copied and near-identical
 * answers, and it is fast and needs no files. Reports say which embedder was used, and the
 * content analysis holds a lexical embedding to a stricter standard (see {@code ContentAnalysis}).
 *
 * <p>The inverse document frequencies come from the answers being embedded, so a word every
 * student used (the question's own terms) carries little weight.
 */
public final class LexicalEmbedder implements SentenceEmbedder {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "of", "to", "in", "on", "at", "for", "and",
            "or", "it", "its", "this", "that", "these", "those", "as", "by", "with", "from", "so", "we", "i");

    private final int dimension;

    public LexicalEmbedder(int dimension) {
        if (dimension < 16) throw new IllegalArgumentException("dimension too small");
        this.dimension = dimension;
    }

    public LexicalEmbedder() {
        this(4096);
    }

    @Override
    public String id() {
        return "lexical TF-IDF (words and word pairs, " + dimension + " hashed dimensions)";
    }

    @Override
    public boolean semantic() {
        return false;
    }

    @Override
    public float[][] embed(List<String> texts) {
        List<Map<String, Integer>> counts = new ArrayList<>(texts.size());
        Map<String, Integer> df = new HashMap<>();
        for (String t : texts) {
            Map<String, Integer> c = features(t);
            counts.add(c);
            for (String f : new HashSet<>(c.keySet())) df.merge(f, 1, Integer::sum);
        }
        int n = texts.size();
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] v = new float[dimension];
            for (Map.Entry<String, Integer> e : counts.get(i).entrySet()) {
                double tf = 1 + Math.log(e.getValue());
                double idf = Math.log((1.0 + n) / (1.0 + df.get(e.getKey()))) + 1.0;
                int h = mix(e.getKey().hashCode());
                int slot = Math.floorMod(h, dimension);
                v[slot] += (float) ((h & 0x40000000) == 0 ? tf * idf : -tf * idf);
            }
            out[i] = SentenceEmbedder.normalise(v);
        }
        return out;
    }

    static Map<String, Integer> features(String text) {
        List<String> words = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) words.add(stem(m.group()));
        Map<String, Integer> c = new HashMap<>();
        String previous = null;
        for (String w : words) {
            if (!STOP.contains(w)) c.merge(w, 1, Integer::sum);
            if (previous != null) c.merge(previous + ' ' + w, 1, Integer::sum);
            previous = w;
        }
        return c;
    }

    /** A light suffix strip, so "forces" meets "force" and "bending" meets "bend". */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 4 && w.endsWith("es")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    /** Spreads String.hashCode's bits, which are poor in the low positions for short strings. */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
