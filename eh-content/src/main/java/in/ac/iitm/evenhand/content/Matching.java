package in.ac.iitm.evenhand.content;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * One question's answers, and for each answer the others that say nearly the same thing.
 *
 * <p>Neighbour lists are computed once (all answers, any assistant, cosine at or above the
 * caliper, most similar first) and the matches are then drawn from them for whatever assistant
 * labels are in force: the real ones, or the shuffled ones of the placebo test. Two answers by
 * the same student to the same question are the same script marked twice, and are the most
 * similar pair there can be.
 */
final class Matching {

    /** How many neighbours are kept per answer; matches and placebo draws come from these. */
    private static final int KEEP = 40;

    final int question;
    final String label;
    final double maxMarks;
    final int[] response;   // global response index of each answer
    final float[][] x;
    final double[] y;       // percentage points of the question
    final int[] grader;
    final int[] student;
    final String[] text;
    final int[] words;
    ContentModel model;
    int[][] neighbour;
    float[][] neighbourSim;

    Matching(int question, String label, double maxMarks, int[] response, float[][] x, double[] y,
             int[] grader, int[] student, String[] text) {
        this.question = question;
        this.label = label;
        this.maxMarks = maxMarks;
        this.response = response;
        this.x = x;
        this.y = y;
        this.grader = grader;
        this.student = student;
        this.text = text;
        this.words = new int[text.length];
        for (int i = 0; i < text.length; i++) words[i] = text[i].isBlank() ? 0 : text[i].strip().split("\\s+").length;
    }

    int size() {
        return y.length;
    }

    /** Fills the neighbour lists: every answer within the caliper, most similar first. */
    void findNeighbours(double caliper) {
        int n = size();
        neighbour = new int[n][];
        neighbourSim = new float[n][];
        IntStream.range(0, n).parallel().forEach(i -> {
            float[] sims = new float[n];
            Integer[] order = new Integer[n];
            int m = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                float s = dot(x[i], x[j]);
                if (s >= caliper) {
                    sims[j] = s;
                    order[m++] = j;
                }
            }
            Integer[] kept = Arrays.copyOf(order, m);
            Arrays.sort(kept, (a, b) -> Float.compare(sims[b], sims[a]) != 0 ? Float.compare(sims[b], sims[a])
                    : Integer.compare(a, b));
            int keep = Math.min(KEEP, m);
            int[] nb = new int[keep];
            float[] ns = new float[keep];
            for (int t = 0; t < keep; t++) {
                nb[t] = kept[t];
                ns[t] = sims[kept[t]];
            }
            neighbour[i] = nb;
            neighbourSim[i] = ns;
        });
    }

    /** A matched comparison of answer {@code i} (assistant {@code ga}) with answer {@code j} ({@code gb}). */
    record Pair(Matching q, int i, int j, double similarity, double difference, double raw,
                double weight, int ga, int gb, int sa, int sb) {}

    /**
     * Each answer's {@code k} most similar answers marked by another assistant, under the given
     * labels. {@code difference} is the bias-corrected gap {@code (y_i - y_j) - content(i, j)}:
     * what is left of the mark gap once what the two answers say differently is allowed for.
     * Each answer carries total weight equal to the question's marks, spread over its matches.
     */
    List<Pair> pairs(int[] labels, int k) {
        List<Pair> out = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            int[] nb = neighbour[i];
            int found = 0;
            int[] picked = new int[k];
            float[] sim = new float[k];
            for (int t = 0; t < nb.length && found < k; t++) {
                int j = nb[t];
                if (labels[j] == labels[i]) continue;
                picked[found] = j;
                sim[found] = neighbourSim[i][t];
                found++;
            }
            for (int f = 0; f < found; f++) {
                int j = picked[f];
                double raw = y[i] - y[j];
                double corrected = raw - model.contentDifference(x, i, j);
                out.add(new Pair(this, i, j, sim[f], corrected, raw, maxMarks / found,
                        labels[i], labels[j], student[i], student[j]));
            }
        }
        return out;
    }

    /** Same-assistant pairs at or above {@code similarity}: the answers one assistant should have marked alike. */
    List<int[]> sameAssistantPairs(double similarity) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < size(); i++) {
            for (int t = 0; t < neighbour[i].length; t++) {
                int j = neighbour[i][t];
                if (j <= i || grader[j] != grader[i] || neighbourSim[i][t] < similarity) continue;
                out.add(new int[] {i, j, Math.round(neighbourSim[i][t] * 1_000_000)});
            }
        }
        return out;
    }

    static float dot(float[] a, float[] b) {
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0;
        int i = 0, n = a.length;
        for (; i + 3 < n; i += 4) {
            s0 += a[i] * b[i];
            s1 += a[i + 1] * b[i + 1];
            s2 += a[i + 2] * b[i + 2];
            s3 += a[i + 3] * b[i + 3];
        }
        for (; i < n; i++) s0 += a[i] * b[i];
        return (s0 + s1) + (s2 + s3);
    }
}
