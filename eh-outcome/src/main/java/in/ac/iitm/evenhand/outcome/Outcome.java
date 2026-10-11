package in.ac.iitm.evenhand.outcome;

import in.ac.iitm.evenhand.core.AllocationEvidence;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.Exposure;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Stage 4: what did the assistants' differences do to grades?
 *
 * <p>The course is graded twice under its own rule: once on the marks as written, and
 * once on the marks every assistant would have written had they marked like the panel
 * average. Doing the second once per bootstrap draw turns "this grade would change" into
 * "this grade changes in 64% of plausible corrections".
 *
 * <p><b>No corrected mark ever becomes a grade.</b> The only per-student output is a
 * recommendation to look at a script again; a grade changes only by a human re-mark.
 */
public final class Outcome {

    /** When a re-check list is emitted, and how long it may be. */
    public record Gate(double alpha, double minProbability, int maxRows) {
        /** Pre-registered before any real marks were seen (design document, section 2.5). */
        public static Gate standard() {
            return new Gate(0.05, 0.20, 20);
        }
    }

    /**
     * One script worth a second look.
     *
     * @param likelyGrade      the letter it most often changes to, when it changes
     * @param probability      share of plausible corrections in which the letter changes
     * @param expectedPoints   expected change in grade points if corrected; positive means
     *                         the student was probably marked down
     */
    public record Row(String student, double totalAsMarked, double totalCorrected,
                      LetterGrade gradeAsMarked, LetterGrade likelyGrade,
                      double probability, double expectedPoints) {}

    /** The re-check list, or the reason there is none. Refusal is a value, not an exception. */
    public sealed interface Shortlist permits Emitted, Refused {}

    public record Emitted(List<Row> rows, double expectedFalseFlags) implements Shortlist {
        public Emitted {
            rows = List.copyOf(rows);
        }
    }

    public record Refused(String reason) implements Shortlist {}

    /**
     * @param gradesAsMarked     the letters the rule gives the marks as written
     * @param gradesCorrected    the letters it gives the corrected marks (point estimate)
     * @param changed            how many letters differ between the two
     * @param changedLow         5th percentile of that count across bootstrap draws
     * @param changedHigh        95th percentile
     * @param assistantShare     share of the variance in course totals that came from who
     *                           marked the script, in percent
     */
    public record Result(double[] totalsAsMarked, double[] totalsCorrected,
                         LetterGrade[] gradesAsMarked, LetterGrade[] gradesCorrected,
                         int changed, int changedLow, int changedHigh,
                         double assistantShare, Shortlist shortlist) {}

    private Outcome() {
    }

    public static Result assess(FittedModel model, List<Response> responses, MarkingDesign design,
                                double[] assessmentWeights, CutoffPolicy policy, Gate gate) {
        Composite composite = new Composite(responses, design, assessmentWeights);
        double[] marked = composite.asMarked();
        double[] corrected = composite.corrected(model.graders().estimates());
        LetterGrade[] gradesMarked = GradingRule.grade(policy, marked);
        LetterGrade[] gradesCorrected = GradingRule.grade(policy, corrected);

        int n = marked.length;
        double[][] draws = model.severityDraws();
        int[] changesPerDraw = new int[draws.length];
        int[] timesChanged = new int[n];
        double[] pointsSum = new double[n];
        int[][] gradeVotes = new int[n][LetterGrade.values().length];
        for (int b = 0; b < draws.length; b++) {
            LetterGrade[] g = GradingRule.grade(policy, composite.corrected(draws[b]));
            for (int i = 0; i < n; i++) {
                if (g[i] != gradesMarked[i]) {
                    gradeVotes[i][g[i].ordinal()]++;
                    timesChanged[i]++;
                    changesPerDraw[b]++;
                    pointsSum[i] += g[i].gradePoints() - gradesMarked[i].gradePoints();
                }
            }
        }

        int changed = 0;
        for (int i = 0; i < n; i++) if (gradesCorrected[i] != gradesMarked[i]) changed++;
        int[] sortedChanges = changesPerDraw.clone();
        Arrays.sort(sortedChanges);
        int low = draws.length == 0 ? changed : sortedChanges[(int) Math.floor(0.05 * (draws.length - 1))];
        int high = draws.length == 0 ? changed : sortedChanges[(int) Math.ceil(0.95 * (draws.length - 1))];

        double[] difference = new double[n];
        for (int i = 0; i < n; i++) difference[i] = marked[i] - corrected[i];
        double varMarked = variance(marked);
        double share = varMarked == 0 ? 0 : 100.0 * variance(difference) / varMarked;

        Shortlist shortlist = shortlist(model, design, gate, marked, corrected, gradesMarked,
                timesChanged, pointsSum, gradeVotes, draws.length);
        return new Result(marked, corrected, gradesMarked, gradesCorrected, changed, low, high, share, shortlist);
    }

    private static Shortlist shortlist(FittedModel model, MarkingDesign design, Gate gate,
                                       double[] marked, double[] corrected, LetterGrade[] gradesMarked,
                                       int[] timesChanged, double[] pointsSum, int[][] gradeVotes, int draws) {
        Exposure exposure = model.verdict().exposure();
        if (exposure.status() == Exposure.Status.NONE) {
            return new Refused("Every student was marked by the same assistants in the same proportions, "
                    + "so no assistant's severity can have moved anyone relative to anyone else.");
        }
        if (exposure.status() == Exposure.Status.NOT_ESTIMABLE) {
            return new Refused("Students met different assistants, but this arrangement cannot measure "
                    + "what that did to them. The audit names the repair.");
        }
        if (model.verdict() instanceof AuditVerdict.EstimableUnderExchangeability
                && design.allocationEvidence().map(AllocationEvidence::followsDepartments).orElse(false)) {
            return new Refused("The comparison rests on the piles having been alike, but the piles follow the "
                    + "department code in the roll number, so an assistant's severity cannot be told apart from "
                    + "the department they marked. Double-mark a few scripts across piles to repair this.");
        }
        if (draws == 0 || Double.isNaN(model.noEffect().pValue())) {
            return new Refused("No uncertainty was computed, so no script can be flagged responsibly.");
        }
        if (!model.noEffect().rejectsAt(gate.alpha())) {
            return new Refused(String.format(java.util.Locale.ROOT,
                    "The assistants' differences are within what chance alone produces (p = %.2f), "
                    + "so no script is flagged.", model.noEffect().pValue()));
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < marked.length; i++) {
            double p = timesChanged[i] / (double) draws;
            if (p < gate.minProbability()) continue;
            int best = 0;
            for (int g = 1; g < gradeVotes[i].length; g++) if (gradeVotes[i][g] > gradeVotes[i][best]) best = g;
            rows.add(new Row(design.studentLabels().get(i), marked[i], corrected[i], gradesMarked[i],
                    LetterGrade.values()[best], p, pointsSum[i] / draws));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> -Math.abs(r.expectedPoints()))
                .thenComparing(Row::student));
        if (rows.size() > gate.maxRows()) rows = rows.subList(0, gate.maxRows());
        double falseFlags = rows.stream().mapToDouble(r -> 1 - r.probability()).sum();
        return new Emitted(rows, falseFlags);
    }

    private static double variance(double[] x) {
        double mean = GradingRule.mean(x);
        double sd = GradingRule.sd(x, mean);
        return sd * sd;
    }
}
