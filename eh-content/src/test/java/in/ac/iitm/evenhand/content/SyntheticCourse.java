package in.ac.iitm.evenhand.content;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * A course whose answers are vectors with a known meaning, for testing the statistics without
 * a language model. Each question has rubric points, each a direction in embedding space; an
 * answer is the sum of the points it makes plus noise, and deserves marks in proportion to
 * the points it makes. The assistant then takes off their severity, and marks are rounded to
 * half marks out of 10.
 */
final class SyntheticCourse {

    enum Allocation { RANDOM, BY_ABILITY, QUESTION_WISE }

    final List<Response> responses = new ArrayList<>();
    final List<String> answers = new ArrayList<>();
    final List<float[]> vectors = new ArrayList<>();
    final MarkingDesign design;
    final double[] trueSeverity;
    /** Response indices whose mark was planted as a slip. */
    final List<Integer> slips = new ArrayList<>();

    private static final int DIM = 64, POINTS = 5, MAX = 10;

    SyntheticCourse(int students, int questions, double[] severity, Allocation allocation, double doubleMarkRate,
                    int plantedSlips, double[][] questionSpecific, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        int graders = severity.length;
        this.trueSeverity = severity.clone();
        double[][][] point = new double[questions][POINTS][];
        for (int q = 0; q < questions; q++) for (int k = 0; k < POINTS; k++) point[q][k] = unit(rng);
        double[] ability = new double[students];
        for (int i = 0; i < students; i++) ability[i] = rng.nextGaussian();
        int[] pile = new int[students];
        if (allocation == Allocation.BY_ABILITY) {
            Integer[] order = new Integer[students];
            for (int i = 0; i < students; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Double.compare(ability[b], ability[a]));
            for (int r = 0; r < students; r++) pile[order[r]] = (int) ((long) r * graders / students);
        } else {
            for (int i = 0; i < students; i++) pile[i] = rng.nextInt(graders);
        }
        List<String> studentLabels = new ArrayList<>(), questionLabels = new ArrayList<>(), graderLabels = new ArrayList<>();
        for (int i = 0; i < students; i++) studentLabels.add("Student " + (i + 1));
        for (int q = 0; q < questions; q++) questionLabels.add("Q" + (q + 1));
        for (int g = 0; g < graders; g++) graderLabels.add("Assistant " + (g + 1));
        int[] position = new int[graders];
        for (int i = 0; i < students; i++) {
            for (int q = 0; q < questions; q++) {
                boolean[] covered = new boolean[POINTS];
                int count = 0;
                for (int k = 0; k < POINTS; k++) {
                    double p = 1 / (1 + Math.exp(-(ability[i] + 0.8 - 0.4 * k)));
                    covered[k] = rng.nextDouble() < p;
                    if (covered[k]) count++;
                }
                double[] v = new double[DIM];
                for (int k = 0; k < POINTS; k++) if (covered[k]) for (int c = 0; c < DIM; c++) v[c] += point[q][k][c];
                double[] noise = unit(rng);
                for (int c = 0; c < DIM; c++) v[c] += 0.35 * noise[c] + 0.6 * point[q][0][c] * 0.5;
                float[] vec = normalise(v);
                StringBuilder text = new StringBuilder();
                for (int k = 0; k < POINTS; k++) if (covered[k]) text.append("point ").append(k).append(". ");
                double deserved = 100.0 * count / POINTS;
                int first = allocation == Allocation.QUESTION_WISE ? q % graders : pile[i];
                int markers = rng.nextDouble() < doubleMarkRate ? 2 : 1;
                for (int m = 0; m < markers; m++) {
                    int g = m == 0 ? first : (first + 1 + rng.nextInt(graders - 1)) % graders;
                    double extra = questionSpecific == null ? 0 : questionSpecific[g][q];
                    double pct = deserved - severity[g] - extra + rng.nextGaussian() * 5;
                    int steps = (int) Math.max(0, Math.min(2 * MAX, Math.round(pct / 100 * 2 * MAX)));
                    DesignCell cell = new DesignCell(i, q, g, 0, position[g]++, m == 0);
                    responses.add(new Response(cell, steps, 2 * MAX, 2));
                    answers.add(text.isEmpty() ? "nothing relevant" : text.toString());
                    vectors.add(vec);
                }
            }
        }
        for (int s = 0; s < plantedSlips; s++) {
            int idx = rng.nextInt(responses.size());
            Response r = responses.get(idx);
            int slipped = r.score() >= r.maxScore() / 2 ? 0 : r.maxScore();
            responses.set(idx, new Response(r.cell(), slipped, r.maxScore(), r.stepsPerMark()));
            slips.add(idx);
        }
        design = MarkingDesign.fromResponses(responses, studentLabels, questionLabels, graderLabels);
    }

    ContentAnalysis.Input input() {
        return new ContentAnalysis.Input(responses, answers, vectors.toArray(new float[0][]), design, "synthetic", true);
    }

    private static double[] unit(SplittableRandom rng) {
        double[] v = new double[DIM];
        double ss = 0;
        for (int c = 0; c < DIM; c++) {
            v[c] = rng.nextGaussian();
            ss += v[c] * v[c];
        }
        double inv = 1 / Math.sqrt(ss);
        for (int c = 0; c < DIM; c++) v[c] *= inv;
        return v;
    }

    private static float[] normalise(double[] v) {
        double ss = 0;
        for (double x : v) ss += x * x;
        float[] out = new float[v.length];
        double inv = ss == 0 ? 0 : 1 / Math.sqrt(ss);
        for (int c = 0; c < v.length; c++) out[c] = (float) (v[c] * inv);
        return out;
    }
}
