package in.ac.iitm.evenhand.content;

import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * How much stronger each TA's pile looks from what its answers say, before anyone's marks are consulted.
 *
 * <p>The answers' content score is learned within piles and applied out of sample, so a difference between
 * piles' mean scores says the piles differ, and says nothing about the TAs. It sees only the part of a script's
 * quality the answers reveal: {@link #scriptSignal} is how large that part is.
 *
 * @param gap          per TA, the pile's mean content score against the average TA's, in percentage points
 * @param scriptSignal squared correlation of the content score with the marks across scripts, within piles
 * @param draws        the gap reweighted by whole students, per draw and TA
 * @param rows         what the gap is computed from, so it can be recomputed on another resample (null if not kept)
 */
public record PileBalance(double[] gap, double scriptSignal, double[][] draws, Rows rows) {

    /** One entry per answer: its student, its TA, its centred content score and its weight. */
    public record Rows(int[] student, int[] grader, double[] centred, double[] weight, int graders) {}

    public PileBalance(double[] gap, double scriptSignal, double[][] draws) {
        this(gap, scriptSignal, draws, null);
    }

    public static PileBalance none(int graders) {
        return new PileBalance(new double[graders], 0, new double[0][]);
    }

    /**
     * The gap on each of the given resamples ({@code counts[b][s]}: how often student s was drawn), or empty if the
     * rows were not kept. Computed on the same resamples as the marks' severities, the two keep their covariance:
     * a resample that puts more strong scripts in a pile raises its content gap and its apparent lenience together.
     */
    public java.util.Optional<double[][]> on(int[][] counts) {
        if (rows == null) return java.util.Optional.empty();
        double[][] out = new double[counts.length][];
        IntStream.range(0, counts.length).parallel().forEach(b -> {
            double[] xi = new double[counts[b].length];
            for (int s = 0; s < xi.length; s++) xi[s] = counts[b][s];
            out[b] = gaps(rows.student, rows.grader, rows.centred, rows.weight, rows.graders, xi);
        });
        return java.util.Optional.of(out);
    }

    static PileBalance of(List<Matching> questions, int graders, int students, int bootstrapDraws, long seed) {
        double[] weight = new double[students], scoreSum = new double[students], markSum = new double[students];
        int total = 0;
        for (Matching q : questions) total += q.size();
        int[] student = new int[total], grader = new int[total];
        double[] centred = new double[total], w = new double[total];
        int at = 0;
        for (Matching q : questions) {
            int n = q.size();
            double[] c = q.model.contentScore;
            double mean = Arrays.stream(c).average().orElse(0);
            double[] pileMark = new double[graders], pileScore = new double[graders];
            int[] count = new int[graders];
            for (int i = 0; i < n; i++) {
                pileMark[q.grader[i]] += q.y[i];
                pileScore[q.grader[i]] += c[i];
                count[q.grader[i]]++;
            }
            for (int g = 0; g < graders; g++) {
                if (count[g] == 0) continue;
                pileMark[g] /= count[g];
                pileScore[g] /= count[g];
            }
            for (int i = 0; i < n; i++, at++) {
                int s = q.student[i], g = q.grader[i];
                weight[s] += q.maxMarks;
                scoreSum[s] += q.maxMarks * (c[i] - pileScore[g]);
                markSum[s] += q.maxMarks * (q.y[i] - pileMark[g]);
                student[at] = s;
                grader[at] = g;
                centred[at] = c[i] - mean;
                w[at] = q.maxMarks;
            }
        }
        double cross = 0, scores = 0, marks = 0;
        for (int s = 0; s < students; s++) {
            if (weight[s] <= 0) continue;
            double a = scoreSum[s] / weight[s], b = markSum[s] / weight[s];
            cross += a * b;
            scores += a * a;
            marks += b * b;
        }
        double signal = cross <= 0 || scores <= 0 || marks <= 0 ? 0 : cross * cross / (scores * marks);
        double[] gap = gaps(student, grader, centred, w, graders, null);
        double[][] draws = new double[bootstrapDraws][];
        IntStream.range(0, bootstrapDraws).parallel().forEach(b -> {
            SplittableRandom rng = new SplittableRandom(seed * 1_000_003L + 77L * b + 1);
            double[] xi = new double[students];
            for (int k = 0; k < students; k++) xi[k] = -Math.log(1 - rng.nextDouble());
            draws[b] = gaps(student, grader, centred, w, graders, xi);
        });
        return new PileBalance(gap, signal, draws, new Rows(student, grader, centred, w, graders));
    }

    private static double[] gaps(int[] student, int[] grader, double[] centred, double[] w, int graders, double[] xi) {
        double[] sum = new double[graders], weight = new double[graders];
        for (int i = 0; i < centred.length; i++) {
            double v = w[i] * (xi == null ? 1 : student[i] < xi.length ? xi[student[i]] : 0);
            sum[grader[i]] += v * centred[i];
            weight[grader[i]] += v;
        }
        double mean = 0;
        int seen = 0;
        for (int g = 0; g < graders; g++) {
            if (weight[g] <= 0) continue;
            sum[g] /= weight[g];
            mean += sum[g];
            seen++;
        }
        mean = seen == 0 ? 0 : mean / seen;
        for (int g = 0; g < graders; g++) sum[g] = weight[g] <= 0 ? 0 : sum[g] - mean;
        return sum;
    }
}
