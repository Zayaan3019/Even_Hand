package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Exposure;
import in.ac.iitm.evenhand.core.MarkingDesign;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Could severity have moved anyone? Answered from the allocation, before any mark.
 *
 * <p>For each student this computes {@code E[n][j]}: the weighted marks of their
 * composite whose mark of record was awarded by assistant {@code j}. A severity profile
 * {@code gamma} shifts student {@code n} by, to first order, {@code sum_j E[n][j] *
 * gamma_j} (in logits, scaled by the local marks-per-logit slope), so relative standing
 * depends only on how {@code E[n]} differs from the class's average. If every row is
 * the same, severity is harmless for relative grades whatever its size: with one marker
 * of record per question, each question's expected mark rises with ability under the
 * same assistant for everyone, so the expected composite still rises with ability.
 *
 * <p>Weights are the marks each question is worth, times its assessment's weight in the
 * course grade. With no weights known, every question counts equally.
 */
public final class ExposureAudit {

    private ExposureAudit() {
    }

    /** Every question weighted equally, for when maxima and assessment weights are not known. */
    public static double[] unitWeights(MarkingDesign design) {
        double[] w = new double[design.questionCount()];
        Arrays.fill(w, 1.0);
        return w;
    }

    /**
     * {@code shares[n][j]}: weighted marks of student {@code n}'s composite whose mark of
     * record came from assistant {@code j}. A response with several marks of record is
     * taken as their average, so each of its assistants carries an equal share.
     *
     * <p>An assessment a student has no marks in at all (they were absent) is counted with
     * the class's own mix of assistants for its questions. Absence is not exposure: the
     * absentee met no assistant on that paper, so none can have moved them on it relative
     * to anyone else. Without this, one absentee from one quiz made every real course look
     * differentially exposed and unmeasurable, and suppressed every re-check list.
     */
    public static double[][] shares(MarkingDesign design, double[] questionWeights) {
        if (questionWeights.length != design.questionCount()) {
            throw new IllegalArgumentException("need one weight per question: " + design.questionCount()
                    + " questions, " + questionWeights.length + " weights");
        }
        for (double w : questionWeights) {
            if (!(w >= 0.0) || !Double.isFinite(w)) {
                throw new IllegalArgumentException("question weights must be finite and >= 0");
            }
        }
        Map<Long, List<Integer>> recordGraders = new LinkedHashMap<>();
        for (DesignCell c : design.cells()) {
            if (!c.ofRecord()) continue;
            long unit = (long) c.student() * design.questionCount() + c.question();
            recordGraders.computeIfAbsent(unit, k -> new ArrayList<>()).add(c.grader());
        }
        double[][] shares = new double[design.studentCount()][design.graderCount()];
        double[][] classMix = new double[design.questionCount()][design.graderCount()];
        int[] present = new int[design.questionCount()];
        for (Map.Entry<Long, List<Integer>> e : recordGraders.entrySet()) {
            int student = (int) (e.getKey() / design.questionCount());
            int question = (int) (e.getKey() % design.questionCount());
            double each = questionWeights[question] / e.getValue().size();
            for (int g : e.getValue()) {
                shares[student][g] += each;
                classMix[question][g] += each;
            }
            present[question]++;
        }
        // Only whole-assessment absence is imputed. A student who sat the paper but has no
        // mark on one question (an optional question, or a different section's paper) is a
        // structural difference and stays one.
        int[] assessmentOf = new int[design.questionCount()];
        boolean[][] sat = new boolean[design.studentCount()][design.assessmentCount()];
        for (DesignCell c : design.cells()) {
            assessmentOf[c.question()] = c.assessment();
            if (c.ofRecord()) sat[c.student()][c.assessment()] = true;
        }
        for (int s = 0; s < design.studentCount(); s++) {
            for (int q = 0; q < design.questionCount(); q++) {
                if (present[q] == 0 || sat[s][assessmentOf[q]]) continue;
                for (int g = 0; g < design.graderCount(); g++) shares[s][g] += classMix[q][g] / present[q];
            }
        }
        return shares;
    }

    /**
     * The exposure verdict.
     *
     * <p>A student-to-student difference {@code (E_n - E_m) . gamma} is estimable exactly
     * when it is a combination of severity differences within groups of mutually
     * comparable assistants, which is when every student carries the same total weight
     * inside every such group. So it is checked group by group: against the groups the
     * marking itself connects, then against the groups connected under exchangeability.
     *
     * @param designClasses          assistants comparable from the marking alone, a partition
     * @param exchangeabilityClasses assistants comparable if the piles were alike, a partition
     */
    public static Exposure assess(double[][] shares,
                                  List<? extends Set<Integer>> designClasses,
                                  List<? extends Set<Integer>> exchangeabilityClasses) {
        int n = shares.length;
        if (n == 0) {
            return new Exposure(Exposure.Status.NONE, 0.0, 0.0);
        }
        int graders = shares[0].length;
        double[] mean = new double[graders];
        double totalSum = 0.0;
        double largestTotal = 0.0;
        for (double[] row : shares) {
            double total = 0.0;
            for (int j = 0; j < graders; j++) {
                mean[j] += row[j] / n;
                total += row[j];
            }
            totalSum += total;
            largestTotal = Math.max(largestTotal, total);
        }
        double meanTotal = totalSum / n;
        double tolerance = 1e-9 * Math.max(1.0, largestTotal);

        double maxSpread = 0.0;
        for (double[] row : shares) {
            double distance = 0.0;
            for (int j = 0; j < graders; j++) distance += Math.abs(row[j] - mean[j]);
            maxSpread = Math.max(maxSpread, distance / 2.0);
        }
        if (maxSpread <= tolerance) {
            return new Exposure(Exposure.Status.NONE, 0.0, meanTotal);
        }
        if (constantWithin(shares, designClasses, tolerance)) {
            return new Exposure(Exposure.Status.ESTIMABLE, maxSpread, meanTotal);
        }
        if (constantWithin(shares, exchangeabilityClasses, tolerance)) {
            return new Exposure(Exposure.Status.UNDER_EXCHANGEABILITY, maxSpread, meanTotal);
        }
        return new Exposure(Exposure.Status.NOT_ESTIMABLE, maxSpread, meanTotal);
    }

    private static boolean constantWithin(double[][] shares, List<? extends Set<Integer>> classes, double tolerance) {
        for (Set<Integer> group : classes) {
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            for (double[] row : shares) {
                double inGroup = 0.0;
                for (int j : group) inGroup += row[j];
                low = Math.min(low, inGroup);
                high = Math.max(high, inGroup);
            }
            if (high - low > tolerance) return false;
        }
        return true;
    }
}
