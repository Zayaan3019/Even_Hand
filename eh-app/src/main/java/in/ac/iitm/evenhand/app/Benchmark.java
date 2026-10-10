package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.AuditReport;
import in.ac.iitm.evenhand.design.IdentifiabilityAudit;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import in.ac.iitm.evenhand.design.sim.CourseSimulator.Archetype;
import in.ac.iitm.evenhand.design.sim.CourseSimulator.SimulatedCourse;
import in.ac.iitm.evenhand.estimate.SeverityEstimator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.ingest.CsvIngest;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.outcome.Composite;
import in.ac.iitm.evenhand.outcome.GradingRule;
import in.ac.iitm.evenhand.outcome.Outcome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Trains the estimator's two shrinkage constants on synthetic IITM courses, then tests
 * everything once on courses it never saw.
 *
 * <ul>
 *   <li><b>Train</b> (seeds 1..T of each of the six course shapes): grid-search the
 *       shrinkage constants for the lowest error in the assistants' severities.</li>
 *   <li><b>Test</b> (seeds T+1..T+M): with those constants frozen, measure severity error,
 *       interval coverage, the no-effect test's false-alarm rate and power, and whether the
 *       re-check list finds students whose grade really was changed by who marked them,
 *       against the obvious alternative of re-checking the scripts nearest a boundary.</li>
 * </ul>
 *
 * <p>The re-check gate (p &lt; 0.05, chance of change &ge; 20%, at most 20 rows) is
 * <em>not</em> trained: it was fixed in the design document before any data existed, so the
 * test cannot have been tuned to flatter it.
 */
final class Benchmark {

    static final double[] STUDENT_GRID = { 0, 5, 10, 20, 40, 80 };
    static final double[] GRADER_GRID = { 0, 5, 10, 20, 50 };

    /** A course read in and audited once, with its answers lined up to the pseudonyms. */
    record Prepared(SimulatedCourse course, CsvIngest.IngestResult ingest, AuditVerdict verdict,
                    double[] weights, double[] trueSeverity, LetterGrade[] markedGrades, LetterGrade[] trueGrades,
                    double[] markedTotals) {

        MarkingDesign design() {
            return ingest.design();
        }
    }

    /** One course's scores on the test. */
    record Score(String course, Archetype archetype, double severitySd, int students, String verdict,
                 boolean estimated, double rmse, int covered, int assistants, boolean rejected,
                 int trueChanges, int flagged, int flaggedRight, int baselineRight, double share) {}

    private Benchmark() {
    }

    static void main(Map<String, String> flags) throws IOException {
        int train = Integer.parseInt(flags.getOrDefault("train", "24"));
        int test = Integer.parseInt(flags.getOrDefault("test", "16"));
        Path out = Path.of(flags.getOrDefault("out", "docs"));
        CutoffPolicy policy = CutoffPolicy.iitmConventionalRelative();

        int shapes = Archetype.values().length;
        System.out.println("Preparing " + shapes * train + " training and " + shapes * test + " test courses...");
        List<Prepared> trainSet = prepareAll(1, train, policy).stream().filter(Benchmark::trainable).toList();
        List<Prepared> testSet = prepareAll(train + 1, train + test, policy);

        // Train: pick the shrinkage with the lowest mean severity error on the training split.
        double bestA = 0, bestS = 0, bestErr = Double.POSITIVE_INFINITY;
        StringBuilder grid = new StringBuilder("| student \\\\ assistant |");
        for (double s : GRADER_GRID) grid.append(String.format(Locale.ROOT, " %.0f |", s));
        grid.append("\n|---|").append("---|".repeat(GRADER_GRID.length)).append('\n');
        for (double a : STUDENT_GRID) {
            grid.append(String.format(Locale.ROOT, "| **%.0f** |", a));
            for (double s : GRADER_GRID) {
                Settings settings = Settings.defaults().withShrinkage(a, s).withDraws(0, 0);
                double err = trainSet.parallelStream().map(p -> severityError(p, settings))
                        .filter(Optional::isPresent).mapToDouble(Optional::get).average().orElse(Double.NaN);
                grid.append(String.format(Locale.ROOT, " %.2f |", err));
                if (err < bestErr) {
                    bestErr = err;
                    bestA = a;
                    bestS = s;
                }
            }
            grid.append('\n');
        }
        System.out.printf(Locale.ROOT, "Trained: student shrinkage %.0f, assistant shrinkage %.0f (train RMSE %.2f points)%n",
                bestA, bestS, bestErr);

        // Test: everything, once, with the trained settings frozen.
        Settings frozen = Settings.defaults().withShrinkage(bestA, bestS).withDraws(100, 49);
        List<Score> scores = testSet.stream().map(p -> score(p, frozen, policy)).toList();
        Files.createDirectories(out.resolve("benchmark"));
        Files.writeString(out.resolve("benchmark").resolve("test-results.csv"), csv(scores), StandardCharsets.UTF_8);
        String md = markdown(train, test, bestA, bestS, bestErr, grid.toString(), scores, trainSet.size());
        Files.writeString(out.resolve("BENCHMARK.md"), md, StandardCharsets.UTF_8);
        System.out.println(md);
    }

    /**
     * A course is used for training only if the tool would stand behind its estimate. A
     * comparison that rests on the piles having been alike, when the piles follow the
     * department code, is refused by the outcome stage, so it must not steer the training
     * either. Such courses still appear in the test, to show the refusal works.
     */
    static boolean trainable(Prepared p) {
        return !(p.verdict() instanceof AuditVerdict.EstimableUnderExchangeability
                && p.design().allocationEvidence().map(e -> e.followsDepartments()).orElse(false));
    }

    static List<Prepared> prepareAll(int fromSeed, int toSeed, CutoffPolicy policy) {
        List<long[]> jobs = new ArrayList<>();
        for (Archetype a : Archetype.values()) {
            for (long seed = fromSeed; seed <= toSeed; seed++) jobs.add(new long[] { a.ordinal(), seed });
        }
        return jobs.parallelStream()
                .map(j -> prepare(CourseSimulator.simulate(Archetype.values()[(int) j[0]], j[1]), policy))
                .toList();
    }

    static Prepared prepare(SimulatedCourse c, CutoffPolicy policy) {
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) c.seed());
        CsvIngest.IngestResult marked = read(c.marksCsv(), Pseudonymiser.withSalt(salt));
        CsvIngest.IngestResult fair = read(c.fairMarksCsv(), Pseudonymiser.withSalt(salt));
        MarkingDesign design = marked.design();
        if (!design.studentLabels().equals(fair.design().studentLabels())) {
            throw new IllegalStateException("fair and marked files did not pseudonymise alike");
        }
        double[] weights = new double[marked.assessmentLabels().size()];
        for (int a = 0; a < weights.length; a++) weights[a] = c.weights().get(marked.assessmentLabels().get(a));

        Composite composite = new Composite(marked.responses(), design, weights);
        double[] markedTotals = composite.asMarked();
        double[] trueTotals = new Composite(fair.responses(), fair.design(), weights).asMarked();
        AuditVerdict verdict = IdentifiabilityAudit.audit(design, composite.questionWeights());

        Main.KeyLookup key = Main.KeyLookup.of(marked.keyFileContents());
        double[] truth = new double[design.graderCount()];
        for (int j = 0; j < truth.length; j++) truth[j] = c.trueSeverity().get(key.real(design.graderLabels().get(j)));

        return new Prepared(c, marked, verdict, weights, truth, GradingRule.grade(policy, markedTotals),
                GradingRule.grade(policy, trueTotals), markedTotals);
    }

    private static CsvIngest.IngestResult read(String csv, Pseudonymiser p) {
        try {
            return CsvIngest.read(new BufferedReader(new StringReader(csv)), p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Root-mean-square error of the severities, in percentage points; empty if not estimable. */
    static Optional<Double> severityError(Prepared p, Settings settings) {
        if (!p.verdict().permitsEstimation()) return Optional.empty();
        FittedModel m = fit(p, settings);
        return Optional.of(rmse(m, centredTruth(p, m)));
    }

    static FittedModel fit(Prepared p, Settings settings) {
        AnchoredDesign anchored = AnchoredDesign.of(p.design(), p.verdict(), Anchoring.standard());
        return SeverityEstimator.fit(anchored, p.ingest().responses(), settings);
    }

    /** Truth on the estimator's scale: centred within each group the audit says shares a scale. */
    static double[] centredTruth(Prepared p, FittedModel m) {
        double[] t = p.trueSeverity().clone();
        for (List<Integer> group : m.groups()) {
            double bar = group.stream().mapToDouble(g -> p.trueSeverity()[g]).average().orElse(0);
            for (int g : group) t[g] -= bar;
        }
        return t;
    }

    private static double rmse(FittedModel m, double[] truth) {
        double ss = 0;
        for (int j = 0; j < truth.length; j++) ss += Math.pow(m.severity(j) - truth[j], 2);
        return Math.sqrt(ss / truth.length);
    }

    static Score score(Prepared p, Settings settings, CutoffPolicy policy) {
        int n = p.markedGrades().length;
        int trueChanges = 0;
        for (int i = 0; i < n; i++) if (p.markedGrades()[i] != p.trueGrades()[i]) trueChanges++;
        String verdict = AuditReport.shortName(p.verdict());
        int assistants = p.design().graderCount();
        if (!p.verdict().permitsEstimation()) {
            return new Score(p.course().name(), p.course().archetype(), p.course().severitySd(), n, verdict, false,
                    Double.NaN, 0, assistants, false, trueChanges, 0, 0, 0, Double.NaN);
        }
        FittedModel m = fit(p, settings);
        double[] truth = centredTruth(p, m);
        int covered = 0;
        for (int j = 0; j < assistants; j++) {
            double[] ci = m.interval95(j);
            if (truth[j] >= ci[0] && truth[j] <= ci[1]) covered++;
        }
        Outcome.Result o = Outcome.assess(m, p.ingest().responses(), p.design(), p.weights(), policy,
                Outcome.Gate.standard());

        int flagged = 0, right = 0, baselineRight = 0;
        if (o.shortlist() instanceof Outcome.Emitted e && !e.rows().isEmpty()) {
            flagged = e.rows().size();
            for (Outcome.Row r : e.rows()) {
                int i = p.design().studentLabels().indexOf(r.student());
                if (p.markedGrades()[i] != p.trueGrades()[i]) right++;
            }
            for (int i : nearestBoundary(p, policy, flagged)) {
                if (p.markedGrades()[i] != p.trueGrades()[i]) baselineRight++;
            }
        }
        return new Score(p.course().name(), p.course().archetype(), p.course().severitySd(), n, verdict, true,
                rmse(m, truth), covered, assistants, m.noEffect().rejectsAt(0.05), trueChanges, flagged, right,
                baselineRight, o.assistantShare());
    }

    /** Today's practice: re-check the k scripts whose totals sit closest to a grade boundary. */
    static List<Integer> nearestBoundary(Prepared p, CutoffPolicy policy, int k) {
        double[] totals = p.markedTotals();
        List<Double> edges = new ArrayList<>(GradingRule.boundaries(policy, totals).values());
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < totals.length; i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> edges.stream()
                .mapToDouble(e -> Math.abs(totals[i] - e)).min().orElse(Double.MAX_VALUE)));
        return order.subList(0, Math.min(k, order.size()));
    }

    private static String csv(List<Score> scores) {
        StringBuilder sb = new StringBuilder("course,archetype,severity_sd_logits,students,verdict,estimated,"
                + "rmse_points,assistants_covered,assistants,no_effect_rejected,true_grade_changes,flagged,"
                + "flagged_correct,nearest_boundary_correct,assistant_share_pct\n");
        for (Score s : scores) {
            sb.append(String.format(Locale.ROOT, "%s,%s,%.2f,%d,%s,%b,%.3f,%d,%d,%b,%d,%d,%d,%d,%.2f%n",
                    s.course(), s.archetype(), s.severitySd(), s.students(), s.verdict().replace(',', ' '),
                    s.estimated(), s.rmse(), s.covered(), s.assistants(), s.rejected(), s.trueChanges(),
                    s.flagged(), s.flaggedRight(), s.baselineRight(), s.share()));
        }
        return sb.toString();
    }

    private static String markdown(int train, int test, double bestA, double bestS, double bestErr, String grid,
                                   List<Score> scores, int trainCourses) {
        StringBuilder md = new StringBuilder();
        md.append("# Benchmark: synthetic IIT Madras courses with known answers\n\n");
        md.append("Generated by `java -jar eh-app/target/even-hand.jar benchmark`. Deterministic: the same command "
                + "reproduces every number. Per-course results: [`benchmark/test-results.csv`](benchmark/test-results.csv).\n\n");
        md.append(String.format(Locale.ROOT, "**Dataset.** %d course shapes x %d seeds for training and x %d further seeds = %d "
                + "held-out test courses (%d students in the test split). %d training courses were used: those "
                + "whose stated assumption the tool would accept (see `Benchmark.trainable`). A quarter of the "
                + "courses have assistants who are truly identical, so false alarms can be counted.%n%n",
                Archetype.values().length, train, test, scores.size(),
                scores.stream().mapToInt(Score::students).sum(), trainCourses));
        md.append(String.format(Locale.ROOT, "**Training.** Grid search over the two shrinkage constants, minimising "
                + "the mean error of the assistants' severities on the training split. Chosen: student %.0f, "
                + "assistant %.0f marks (training RMSE %.2f percentage points).%n%n", bestA, bestS, bestErr));
        md.append(grid).append('\n');
        md.append("**Test** (held-out courses, settings frozen, 100 bootstrap draws, 49 no-effect simulations):\n\n");
        md.append("| Course shape | Courses | Audit verdict | Grades truly moved by who marked | Severity RMSE (points) | "
                + "95% interval coverage | False alarms (identical TAs) | Detected (TAs differ) | Re-check precision | "
                + "Nearest-boundary precision |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        Map<Archetype, List<Score>> by = new LinkedHashMap<>();
        for (Score s : scores) by.computeIfAbsent(s.archetype(), k -> new ArrayList<>()).add(s);
        for (Map.Entry<Archetype, List<Score>> e : by.entrySet()) md.append(row(e.getKey().name(), e.getValue()));
        md.append(row("**All**", scores));
        md.append("\n*Grades truly moved*: share of students whose letter under the course's rule differs from the "
                + "letter an average TA would have given them; known only in simulation. It is not zero for "
                + "one-TA-per-question marking (SMALL_ELECTIVE): marks are bounded by zero and full marks, so a harsh "
                + "TA there is harmless only to first order (ASSUMPTIONS A12), though about 4x less harmful than "
                + "script-wise piles.\n");
        md.append("\n*Re-check precision*: of the scripts Even Hand flagged, the share whose grade really was changed by "
                + "who marked them (known only in simulation). *Nearest-boundary precision*: the same number of scripts "
                + "chosen the way moderation is done today, by closeness to a grade boundary.\n");
        return md.toString();
    }

    private static String row(String name, List<Score> s) {
        List<Score> est = s.stream().filter(Score::estimated).toList();
        String verdicts = s.stream().map(Score::verdict).distinct().sorted().reduce((a, b) -> a + ", " + b).orElse("");
        double rmse = est.stream().mapToDouble(Score::rmse).average().orElse(Double.NaN);
        int covered = est.stream().mapToInt(Score::covered).sum();
        int assistants = est.stream().mapToInt(Score::assistants).sum();
        long nulls = est.stream().filter(x -> x.severitySd() == 0).count();
        long falseAlarms = est.stream().filter(x -> x.severitySd() == 0 && x.rejected()).count();
        long real = est.stream().filter(x -> x.severitySd() > 0).count();
        long detected = est.stream().filter(x -> x.severitySd() > 0 && x.rejected()).count();
        int flagged = est.stream().mapToInt(Score::flagged).sum();
        int right = est.stream().mapToInt(Score::flaggedRight).sum();
        int base = est.stream().mapToInt(Score::baselineRight).sum();
        int moved = s.stream().mapToInt(Score::trueChanges).sum();
        int students = s.stream().mapToInt(Score::students).sum();
        return String.format(Locale.ROOT, "| %s | %d | %s | %.1f%% | %s | %s | %s | %s | %s | %s |%n", name, s.size(), verdicts,
                100.0 * moved / students,
                est.isEmpty() ? "not estimated" : String.format(Locale.ROOT, "%.2f", rmse),
                assistants == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * covered / assistants),
                nulls == 0 ? "-" : falseAlarms + "/" + nulls,
                real == 0 ? "-" : detected + "/" + real,
                flagged == 0 ? "-" : String.format(Locale.ROOT, "%.0f%% (%d/%d)", 100.0 * right / flagged, right, flagged),
                flagged == 0 ? "-" : String.format(Locale.ROOT, "%.0f%% (%d/%d)", 100.0 * base / flagged, base, flagged));
    }
}
