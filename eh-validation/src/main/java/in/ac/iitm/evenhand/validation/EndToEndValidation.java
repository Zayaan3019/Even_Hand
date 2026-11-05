package in.ac.iitm.evenhand.validation;

import in.ac.iitm.evenhand.app.CourseConfig;
import in.ac.iitm.evenhand.app.Pipeline;
import in.ac.iitm.evenhand.content.ContentFit;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.ingest.CsvIngest;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.ingest.XlsxReader;
import in.ac.iitm.evenhand.outcome.GradingRule;
import in.ac.iitm.evenhand.outcome.Outcome;
import in.ac.iitm.evenhand.validation.UntValidation.Scenario;
import in.ac.iitm.evenhand.validation.UntValidation.Ta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.function.UnaryOperator;

/**
 * The whole product on real, independently double-graded answers: a marks file as text goes through
 * {@link Pipeline#run} exactly as the app sends it (file reader, pseudonyms, audit, both estimators,
 * the choice between them, regrading, the re-check gate), and the final output is scored against what
 * the double grading shows. {@link UntValidation} measures the estimators alone; this measures what a
 * professor would actually be told, including the letter grades and the re-check list.
 *
 * <p>Because both graders marked every answer, the grade a script "should" get is known: the grade its
 * total earns under the course's rule with each answer given the average of the TAs' marks. So the
 * re-check list can be scored on real grading behaviour, not only in simulation.
 *
 * <p>The second half feeds the same real data in damaged or awkward forms and records whether the run
 * survives and how far the answer moves.
 *
 * <pre>java -cp ... EndToEndValidation &lt;dataset dir&gt; [--runs 60] [--out dir] [--xlsx file --csv file]</pre>
 */
public final class EndToEndValidation {

    /** ASAP was never used for tuning, so all of it is held out; UNT keeps to its odd-numbered questions. */
    private static boolean allQuestions;
    private static boolean threeRealTas;
    private static int stabilityDeals = 10, stabilitySalts = 30;
    /** Scripts kept per assessment in each deal, drawn afresh for each; 0 keeps them all. */
    private static int perAssessment;
    private static java.util.Set<String> onlyScenarios = java.util.Set.of();

    static final String HEADER = "roll_no,assessment,question,max_marks,marks,ta,answer";

    /** The published scenarios, plus a small real-sized gap to show where detection runs out. */
    static List<Scenario> scenarios() {
        List<Scenario> all = new ArrayList<>(UntValidation.SCENARIOS.stream().filter(s -> s.slipRate() == 0).toList());
        all.add(new Scenario("small-gap", "one real grader's marks, B 3 points harsher (inside the 1.5-point margin per TA), random deal",
                List.of(new Ta("A", false, 0), new Ta("B", false, -3)), false, 0));
        if (threeRealTas) {
            all.add(new Scenario("three-real", "the three real TAs, scripts dealt at random",
                    List.of(new Ta("First", true, 0), new Ta("Second", false, 0), new Ta("Third", false, 0, true)), false, 0));
        }
        return all;
    }

    /** One deal written out as the file a course would keep, with everything known about it. */
    record Deal(String csv, List<String> rolls, List<String> taNames, double[] truthSeverity, double[] truthTotals,
                double[] markedTotals) {}

    static final class Tally {
        int runs, failures, answersChosen, notEstimable, covered, intervals, differ, listed, listRows, tp, moved, students;
        int agreeMarked, agreeCorrected, baselineTp, totalMismatch, likelyRight;
        double sse, count, truthFirst, changedSum, chanceShown;
        int agreeMarkedSig, agreeCorrectedSig, studentsSig, changedQuiet, quietRuns;
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        double seconds;

        double rmse() {
            return Math.sqrt(sse / Math.max(1, count));
        }
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 1; i < args.length - 1; i++) if (args[i].startsWith("--")) flags.put(args[i].substring(2), args[i + 1]);
        String dataset = flags.getOrDefault("dataset", "unt");
        allQuestions = !dataset.equals("unt");
        threeRealTas = dataset.equals("cityu");
        UntDataset data = switch (dataset) {
            case "asap" -> AsapDataset.load(Path.of(args[0]));
            case "aes" -> OpenDatasets.asapEssays(Path.of(args[0]));
            case "jorgpt" -> OpenDatasets.jorGpt(Path.of(args[0]));
            case "cityu" -> OpenDatasets.cityU(Path.of(args[0]));
            default -> UntDataset.load(Path.of(args[0]));
        };
        if ("load".equals(flags.get("only"))) {
            for (UntDataset.Question q : data.questions()) {
                double gap = q.answers().stream().mapToDouble(a -> Math.abs(a.ta() - a.second())).average().orElse(0);
                double words = q.answers().stream().mapToInt(a -> a.text().split("\\s+").length).average().orElse(0);
                System.out.printf(Locale.ROOT, "%s max %.0f answers %d mean gap %.2f mean words %.0f key %s%n", q.id(), q.maxMarks(),
                        q.answers().size(), gap, words, !q.key().isEmpty());
            }
            System.out.println(data.answerCount() + " answers, " + data.questions().size() + " questions");
            return;
        }
        stabilityDeals = Integer.parseInt(flags.getOrDefault("stability-deals", "10"));
        stabilitySalts = Integer.parseInt(flags.getOrDefault("stability-salts", "30"));
        int runs = Integer.parseInt(flags.getOrDefault("runs", "60"));
        perAssessment = Integer.parseInt(flags.getOrDefault("scripts", "0"));
        if (flags.containsKey("scenarios")) onlyScenarios = java.util.Set.of(flags.get("scenarios").split(","));
        StringBuilder md = new StringBuilder();
        md.append("Generated by `eh-validation`'s `EndToEndValidation`: ").append(runs).append(" deals per scenario of the ")
          .append(describe(dataset, data)).append(", each sent through the file reader and ")
          .append("`Pipeline.run` with the product's default settings. Severity in percentage points of a question's maximum.\n\n");
        if (perAssessment > 0) md.append("Each deal draws ").append(perAssessment)
          .append(" whole scripts per assessment afresh from the full dataset, the size of a large course, so the run fits GitHub's time limit.\n\n");

        String only = flags.getOrDefault("only", "all");
        if (only.equals("stability")) {
            stabilitySection(data, flags, md);
            flush(flags, md);
            return;
        }
        if (only.equals("robustness")) {
            md.append("## Robustness: the same real data, damaged or awkward\n\n");
            robustness(data, flags, md);
            flush(flags, md);
            return;
        }
        md.append("## Accuracy of the final output\n\n");
        md.append("| Scenario | True severity, first TA | Verdict(s) | Piles found to differ | Final severity RMSE | 95% coverage | Says TAs differ (no-effect p < 0.05) | Re-check list issued | Grades truly moved | Grade right as marked | Grade right after correction | Same, runs that said they differ | Grades reported changing (mean; in runs that did not) | Re-check precision | Mean chance the app shows | Nearest-boundary precision | Re-check recall |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Scenario sc : scenarios()) {
            if (!onlyScenarios.isEmpty() && !onlyScenarios.contains(sc.name())) continue;
            Tally t = new Tally();
            for (int run = 0; run < runs; run++) score(sc, data, run, t);
            String row = row(sc, t);
            System.out.println(row);
            md.append(row).append('\n');
            flush(flags, md);
        }
        md.append("\n- *Grades truly moved*: share of scripts whose letter as marked differs from the letter their average-of-TAs marks earn.\n");
        md.append("- *Precision*: of the scripts listed, the share truly moved. *Nearest-boundary*: the same number of scripts chosen by closeness to a grade boundary, as moderation is done today. Both pooled over runs that issued a list.\n");
        md.append("- *Recall*: of the truly moved scripts in those runs, the share listed. The list is capped at 20.\n");
        md.append("- Scripts stand in for students: the dataset has no student identity across assignments, so each script is a student who sat one paper.\n\n");

        if (only.equals("accuracy")) {
            flush(flags, md);
            return;
        }
        stabilitySection(data, flags, md);

        md.append("## Robustness: the same real data, damaged or awkward\n\n");
        robustness(data, flags, md);
        flush(flags, md);
        System.out.println("done");
    }

    private static void stabilitySection(UntDataset data, Map<String, String> flags, StringBuilder md) throws IOException {
        md.append("## Stability: the same data, only the pseudonym salt differs\n\n");
        md.append("Everything is numbered and seeded by the pseudonyms, so a salt is one roll of the analysis's random splits and draws. A corrected mark changes the salt. Nothing real has changed here, so nothing should move. " + stabilitySalts + " salts on each of " + stabilityDeals + " deals.\n\n");
        md.append("| Scenario | Deals where \"the piles differ\" flipped | Mean range of severity across salts (points) | Largest range | Mean range of grades changing | Deals where the re-check list came and went |\n|---|---|---|---|---|---|\n");
        for (String name : List.of("random", "null", "null-unfair", "planted-unfair", "four")) {
            if (!onlyScenarios.isEmpty() && !onlyScenarios.contains(name)) continue;
            String row = stability(scenarios().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow(), data);
            System.out.println(row);
            md.append(row).append('\n');
            flush(flags, md);
        }
        md.append('\n');
    }

    private static String describe(String dataset, UntDataset data) {
        String size = data.answerCount() + " answers to " + data.questions().size() + " questions, none of it used for tuning";
        return switch (dataset) {
            case "asap" -> "real ASAP short answers (" + size + ", no answer key)";
            case "aes" -> "real ASAP essays (" + size + ", no answer key)";
            case "jorgpt" -> "real JorGPT answers (" + size + ", whole scripts by real students; the second grader in \"random\" and \"four\" is a language model, not a person)";
            case "cityu" -> "real CityU Operating Systems answers (" + size + ", each marked by three real TAs)";
            default -> "real UNT answers (odd-numbered questions, never used for tuning)";
        };
    }

    private static void flush(Map<String, String> flags, StringBuilder md) throws IOException {
        if (!flags.containsKey("out")) return;
        Path out = Path.of(flags.get("out"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("VALIDATION-END-TO-END.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static String row(Scenario sc, Tally t) {
        double n = Math.max(1, t.runs);
        String verdicts = t.verdicts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("-");
        return String.format(Locale.ROOT,
                "| %s | %+.1f | %s | %.0f%% | **%.2f** | %.0f%% | %.0f%% | %.0f%% | %.1f%% | %.1f%% | %.1f%% | %s | %s | %s | %s | %s | %s |%s",
                sc.name(), t.truthFirst / n, verdicts, 100 * t.answersChosen / n, t.rmse(),
                100.0 * t.covered / Math.max(1, t.intervals), 100 * t.differ / n, 100 * t.listed / n,
                100.0 * t.moved / Math.max(1, t.students), 100.0 * t.agreeMarked / Math.max(1, t.students),
                100.0 * t.agreeCorrected / Math.max(1, t.students),
                t.studentsSig == 0 ? "-" : String.format(Locale.ROOT, "%.1f%% to %.1f%%", 100.0 * t.agreeMarkedSig / t.studentsSig, 100.0 * t.agreeCorrectedSig / t.studentsSig),
                String.format(Locale.ROOT, "%.0f; %s", t.changedSum / n, t.quietRuns == 0 ? "-" : String.format(Locale.ROOT, "%.0f", t.changedQuiet / (double) t.quietRuns)),
                t.listRows == 0 ? "-" : String.format(Locale.ROOT, "%.0f%% (%d/%d)", 100.0 * t.tp / t.listRows, t.tp, t.listRows),
                t.listRows == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * t.chanceShown / t.listRows),
                t.listRows == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * t.baselineTp / t.listRows),
                t.listRows == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * t.tp / Math.max(1, t.likelyRight)),
                (t.failures + t.totalMismatch) == 0 ? "" : " FAILURES " + t.failures + " MISMATCH " + t.totalMismatch);
    }

    private static String stability(Scenario sc, UntDataset data) {
        int deals = stabilityDeals, salts = stabilitySalts, flipped = 0, listFlips = 0;
        double rangeSum = 0, rangeMax = 0, gradeRangeSum = 0;
        for (int d = 0; d < deals; d++) {
            Deal deal = deal(sc, sample(data, 31_337L + d), new SplittableRandom(31_337L + d));
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY, gLo = Double.POSITIVE_INFINITY, gHi = Double.NEGATIVE_INFINITY;
            int answers = 0, listed = 0;
            for (int s = 0; s < salts; s++) {
                byte[] salt = new byte[32];
                new Random(1000L * d + s).nextBytes(salt);
                Pseudonymiser p = Pseudonymiser.withSalt(salt);
                try {
                    Pipeline.Run r = Pipeline.run(deal.csv, p, CourseConfig.defaults(sc.name()), Pipeline.Options.defaults(), Pipeline.Progress.none());
                    if (adjusted(r)) answers++;
                    if (r.model().isPresent()) {
                        int j = r.ingest().design().graderLabels().indexOf(labels(p).get(deal.taNames.get(0)));
                        double v = r.model().get().severity(j);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                    }
                    if (r.outcome().isPresent()) {
                        gLo = Math.min(gLo, r.outcome().get().changed());
                        gHi = Math.max(gHi, r.outcome().get().changed());
                        if (r.outcome().get().shortlist() instanceof Outcome.Emitted) listed++;
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            if (answers > 0 && answers < salts) flipped++;
            if (listed > 0 && listed < salts) listFlips++;
            rangeSum += hi - lo;
            rangeMax = Math.max(rangeMax, hi - lo);
            gradeRangeSum += gHi - gLo;
        }
        return String.format(Locale.ROOT, "| %s | %d of %d | %.2f | %.2f | %.1f | %d of %d |", sc.name(), flipped, deals,
                rangeSum / deals, rangeMax, gradeRangeSum / deals, listFlips, deals);
    }

    /** Whether the answers showed the piles differ, or were the only way to compare the TAs. */
    private static boolean adjusted(Pipeline.Run r) {
        return r.verdict() instanceof AuditVerdict.EstimableFromContent
                || r.content().map(c -> ContentFit.choose(r.markingVerdict(), r.marksModel(), c).adjustForPiles()).orElse(false);
    }

    // ------------------------------------------------------------------ one deal, scored

    static void score(Scenario sc, UntDataset data, int run, Tally t) {
        SplittableRandom rng = new SplittableRandom(4242L * 1_000_003L + run * 7919L + sc.name().hashCode());
        Deal d = deal(sc, sample(data, 4242L + run), rng);
        t.runs++;
        t.truthFirst += d.truthSeverity[0];
        Pseudonymiser p;
        Pipeline.Run r;
        try {
            p = CsvIngest.pseudonymiserFor(d.csv);
            r = Pipeline.run(d.csv, p, CourseConfig.defaults(sc.name()), Pipeline.Options.defaults(), Pipeline.Progress.none());
        } catch (Exception e) {
            t.failures++;
            System.out.println("FAILED " + sc.name() + " run " + run + ": " + e);
            return;
        }
        t.verdicts.merge(r.verdict().getClass().getSimpleName().replace("Estimable", "Est.").replace("UnderExchangeability", "UnderExch"), 1, Integer::sum);
        if (adjusted(r)) t.answersChosen++;
        if (r.model().isEmpty() || r.outcome().isEmpty()) {
            t.notEstimable++;
            return;
        }
        Map<String, String> labels = labels(p);
        FittedModel m = r.model().get();
        List<String> graderLabels = r.ingest().design().graderLabels();
        for (int g = 0; g < d.taNames.size(); g++) {
            int j = graderLabels.indexOf(labels.get(d.taNames.get(g)));
            if (j < 0) continue;
            double truth = d.truthSeverity[g];
            t.sse += Math.pow(m.severity(j) - truth, 2);
            t.count++;
            double[] ci = m.interval95(j);
            if (ci[0] <= truth && truth <= ci[1]) t.covered++;
            t.intervals++;
        }
        if (m.noEffect().rejectsAt(0.05)) t.differ++;

        // Students in the product's own order.
        Outcome.Result o = r.outcome().get();
        List<String> studentLabels = r.ingest().design().studentLabels();
        int n = studentLabels.size();
        double[] truth = new double[n], ownMarked = new double[n];
        for (int i = 0; i < d.rolls.size(); i++) {
            int k = studentLabels.indexOf(labels.get(d.rolls.get(i)));
            if (k < 0) continue;
            truth[k] = d.truthTotals[i];
            ownMarked[k] = d.markedTotals[i];
        }
        for (int k = 0; k < n; k++) if (Math.abs(ownMarked[k] - o.totalsAsMarked()[k]) > 1e-6) t.totalMismatch++;
        CutoffPolicy policy = CutoffPolicy.iitmConventionalRelative();
        LetterGrade[] right = GradingRule.grade(policy, truth);
        boolean[] moved = new boolean[n];
        int movedHere = 0;
        for (int k = 0; k < n; k++) {
            moved[k] = o.gradesAsMarked()[k] != right[k];
            if (moved[k]) movedHere++;
            if (!moved[k]) t.agreeMarked++;
            if (o.gradesCorrected()[k] == right[k]) t.agreeCorrected++;
        }
        t.changedSum += o.changed();
        if (m.noEffect().rejectsAt(0.05)) {
            t.studentsSig += n;
            for (int k = 0; k < n; k++) {
                if (!moved[k]) t.agreeMarkedSig++;
                if (o.gradesCorrected()[k] == right[k]) t.agreeCorrectedSig++;
            }
        } else {
            t.quietRuns++;
            t.changedQuiet += o.changed();
        }
        t.moved += movedHere;
        t.students += n;
        if (o.shortlist() instanceof Outcome.Emitted e) {
            t.listed++;
            t.likelyRight += movedHere;
            for (Outcome.Row row : e.rows()) {
                t.listRows++;
                t.chanceShown += row.probability();
                if (moved[studentLabels.indexOf(row.student())]) t.tp++;
            }
            for (int k : nearestBoundary(policy, o.totalsAsMarked(), e.rows().size())) if (moved[k]) t.baselineTp++;
        }
    }

    private static int[] nearestBoundary(CutoffPolicy policy, double[] marked, int count) {
        Map<LetterGrade, Double> cuts = GradingRule.boundaries(policy, marked);
        Integer[] order = new Integer[marked.length];
        double[] gap = new double[marked.length];
        for (int i = 0; i < marked.length; i++) {
            order[i] = i;
            gap[i] = Double.POSITIVE_INFINITY;
            for (double c : cuts.values()) gap[i] = Math.min(gap[i], Math.abs(marked[i] - c));
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> gap[i]));
        int[] out = new int[Math.min(count, marked.length)];
        for (int i = 0; i < out.length; i++) out[i] = order[i];
        return out;
    }

    /** Real identifier to label, read back from the key the instructor would hold. */
    static Map<String, String> labels(Pseudonymiser p) {
        Map<String, String> out = new HashMap<>();
        boolean body = false;
        for (String line : p.keyFileContents().split("\n")) {
            if (line.startsWith("identifier,")) {
                body = true;
            } else if (body) {
                String[] f = line.split(",");
                if (f.length >= 3) out.put(f[0].replace("\"", ""), f[1].replace("\"", ""));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ one deal, written as a file

    /**
     * The dataset cut to {@link #perAssessment} scripts per assessment, chosen by {@code seed}: whole scripts, so a
     * student keeps every answer. Unchanged when no cut is asked for or the assessment is already that small.
     */
    static UntDataset sample(UntDataset data, long seed) {
        if (perAssessment <= 0) return data;
        Map<String, java.util.LinkedHashSet<String>> scripts = new LinkedHashMap<>();
        for (UntDataset.Question q : data.questions()) {
            for (int z = 0; z < q.answers().size(); z++) {
                String s = UntValidation.script(q, z);
                scripts.computeIfAbsent(UntValidation.assignmentOf(s), k -> new java.util.LinkedHashSet<>()).add(s);
            }
        }
        java.util.Set<String> keep = new java.util.HashSet<>();
        Random rng = new Random(seed);
        for (java.util.LinkedHashSet<String> set : scripts.values()) {
            List<String> shuffled = new ArrayList<>(set);
            Collections.shuffle(shuffled, rng);
            keep.addAll(shuffled.subList(0, Math.min(perAssessment, shuffled.size())));
        }
        List<UntDataset.Question> out = new ArrayList<>();
        for (UntDataset.Question q : data.questions()) {
            List<UntDataset.Answer> kept = new ArrayList<>();
            for (int z = 0; z < q.answers().size(); z++) {
                UntDataset.Answer a = q.answers().get(z);
                if (!keep.contains(UntValidation.script(q, z))) continue;
                kept.add(a.student() >= 0 ? a : new UntDataset.Answer(a.text(), a.ta(), a.second(), a.third(), z));
            }
            out.add(new UntDataset.Question(q.id(), q.maxMarks(), q.key(), kept));
        }
        return new UntDataset(out);
    }

    static Deal deal(Scenario sc, UntDataset data, SplittableRandom rng) {
        return deal(sc, data, rng, 1, null);
    }

    /**
     * Whole scripts dealt to one TA, as a course does: the same rule as {@link UntValidation#course}, but
     * written out as a marks file. {@code copies} repeats the course under new roll numbers (a load test);
     * {@code perQuestion}, when set, gives the TA for question number k instead of dealing scripts.
     */
    static Deal deal(Scenario sc, UntDataset data, SplittableRandom rng, int copies, int[] perQuestion) {
        int graders = sc.tas().size();
        List<String> taNames = new ArrayList<>();
        for (int g = 0; g < graders; g++) taNames.add("Grader " + (char) ('A' + g));

        List<UntDataset.Question> selected = new ArrayList<>();
        for (int qi = allQuestions ? 0 : 1; qi < data.questions().size(); qi += allQuestions ? 1 : 2) selected.add(data.questions().get(qi));

        Map<String, double[]> quality = new LinkedHashMap<>();
        Map<String, Double> assessmentMax = new LinkedHashMap<>();
        for (UntDataset.Question q : selected) {
            assessmentMax.merge(assessment(q), q.maxMarks(), Double::sum);
            for (int z = 0; z < q.answers().size(); z++) {
                double[] s = quality.computeIfAbsent(UntValidation.script(q, z), k -> new double[2]);
                s[0] += 100.0 * q.answers().get(z).ta() / q.maxMarks();
                s[1]++;
            }
        }
        Map<String, List<Double>> byAssignment = new LinkedHashMap<>();
        quality.forEach((k, v) -> byAssignment.computeIfAbsent(UntValidation.assignmentOf(k), a -> new ArrayList<>()).add(v[0] / v[1]));

        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (UntDataset.Question q : selected) {
            if (!q.key().isEmpty()) {
                csv.append(line("KEY", assessment(q), question(q), q.maxMarks(), "", "", q.key()));
            }
        }
        List<String> rolls = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        double[] meanPct = new double[graders];
        double wTotal = 0;
        double weight = 100.0 / assessmentMax.size();
        List<double[]> totals = new ArrayList<>();
        // A question answered twice by one student counts once: with no role column the product takes the first mark in
        // the file as the mark of record (and says so), and so does this.
        Map<String, double[]> units = new LinkedHashMap<>();

        for (int copy = 0; copy < copies; copy++) {
            Map<String, Integer> dealt = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> e : quality.entrySet()) {
                double q = e.getValue()[0] / e.getValue()[1];
                double[] all = byAssignment.get(UntValidation.assignmentOf(e.getKey())).stream().mapToDouble(Double::doubleValue).toArray();
                double median = UntValidation.median(all);
                boolean strong = q > median || (q == median && rng.nextBoolean());
                dealt.put(e.getKey(), UntValidation.allocate(sc, strong, rng));
            }
            int k = 0;
            for (UntDataset.Question q : selected) {
                for (int z = 0; z < q.answers().size(); z++) {
                    UntDataset.Answer a = q.answers().get(z);
                    String script = UntValidation.script(q, z);
                    String roll = roll(script, copy);
                    Integer at = index.get(roll);
                    if (at == null) {
                        at = rolls.size();
                        index.put(roll, at);
                        rolls.add(roll);
                        totals.add(new double[2]);
                    }
                    int g = perQuestion != null ? perQuestion[k] : dealt.get(script);
                    double given = mark(sc.tas().get(g), a, q);
                    double mean = 0;
                    for (int h = 0; h < graders; h++) {
                        double mh = mark(sc.tas().get(h), a, q);
                        mean += mh / graders;
                        meanPct[h] += 100.0 * mh;
                    }
                    wTotal += q.maxMarks();
                    double max = assessmentMax.get(assessment(q));
                    double[] u = units.computeIfAbsent(at + "\u0000" + q.id(), x -> new double[4]);
                    if (u[2]++ == 0) {
                        u[0] = weight * mean / max;
                        u[1] = weight * given / max;
                        u[3] = at;
                    }
                    csv.append(line(roll, assessment(q), question(q), q.maxMarks(), plain(given), taNames.get(g), a.text()));
                }
                k++;
            }
        }
        for (double[] u : units.values()) {
            totals.get((int) u[3])[0] += u[0];
            totals.get((int) u[3])[1] += u[1];
        }
        double[] truth = new double[graders];
        double bar = 0;
        for (int g = 0; g < graders; g++) {
            meanPct[g] /= wTotal;
            bar += meanPct[g] / graders;
        }
        for (int g = 0; g < graders; g++) truth[g] = bar - meanPct[g];
        double[] truthTotals = new double[rolls.size()], marked = new double[rolls.size()];
        for (int i = 0; i < rolls.size(); i++) {
            truthTotals[i] = totals.get(i)[0];
            marked[i] = totals.get(i)[1];
        }
        return new Deal(csv.toString(), rolls, taNames, truth, truthTotals, marked);
    }

    /** As {@link UntValidation#mark}, to the nearest 0.05 rather than half a mark, so small offsets survive. */
    private static double mark(Ta ta, UntDataset.Answer a, UntDataset.Question q) {
        double shifted = ta.base(a) + ta.offsetPoints() / 100.0 * q.maxMarks();
        return Math.max(0, Math.min(q.maxMarks(), Math.round(shifted * 20) / 20.0));
    }

    private static String assessment(UntDataset.Question q) {
        int a = Integer.parseInt(q.id().substring(0, q.id().indexOf('.')));
        return a <= 10 ? "Assignment " + a : "Exam " + (a - 10);
    }

    private static String question(UntDataset.Question q) {
        return "Q" + q.id().substring(q.id().indexOf('.') + 1);
    }

    private static String roll(String script, int copy) {
        String[] p = script.split("-");
        return String.format(Locale.ROOT, "UNT%d%02d%03d", copy, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
    }

    private static String plain(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static String line(String roll, String assessment, String question, double max, String marks, String ta, String answer) {
        return roll + "," + assessment + "," + question + "," + plain(max) + "," + marks + "," + ta + "," + csvField(answer) + "\n";
    }

    private static String csvField(String v) {
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------ robustness

    private record Case(String name, String expected, UnaryOperator<String> change) {}

    private static void robustness(UntDataset data, Map<String, String> flags, StringBuilder md) throws IOException {
        Scenario real = UntValidation.SCENARIOS.get(0);
        data = sample(data, 99);
        Deal base = deal(real, data, new SplittableRandom(99));
        Random rnd = new Random(7);
        md.append("Baseline: one random deal of the two real graders.\n\n");
        md.append("| Case | Expected | Survived | Rows read | Verdict | Answers | Largest severity shift (points) | Grades changing | Re-check | Notes |\n|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, Double> baseSev = new LinkedHashMap<>();
        attempt(new Case("Baseline", "reference", s -> s), base, base.csv, baseSev, md, null);

        List<Case> cases = List.of(
            new Case("Rows in a different order", "same answer, small shift only", s -> shuffled(s, new Random(1))),
            new Case("No answer key", "works; small shift", s -> dropLines(s, l -> l.startsWith("KEY,"))),
            new Case("20% of answers blank", "works; small shift", s -> blankAnswers(s, 0.20, new Random(2))),
            new Case("80% of answers blank", "falls back to marks, says so", s -> blankAnswers(s, 0.80, new Random(3))),
            new Case("No answer column", "marks only", EndToEndValidation::dropAnswerColumn),
            new Case("BOM and Windows line endings", "identical severity", s -> "﻿" + s.replace("\n", "\r\n")),
            new Case("Loosely named columns", "identical severity", s -> "Roll No,Assessment,Question,Max Marks,Marks,Marker,Answer" + s.substring(s.indexOf('\n'))),
            new Case("5% of rows missing (absences)", "works; small shift", s -> dropLines(s, l -> !l.startsWith("roll_no") && !l.startsWith("KEY,") && new Random(l.hashCode()).nextDouble() < 0.05)),
            new Case("2% of marks written AB", "treated as unanswered", s -> mapMarks(s, 0.02, "AB", new Random(4))),
            new Case("1% of marks above the maximum", "reported, not silently used", s -> mapMarks(s, 0.01, "99", new Random(5))),
            new Case("1% of marks not numbers", "reported, not silently used", s -> mapMarks(s, 0.01, "abc", new Random(6))),
            new Case("Roll numbers and TA names inside answers", "removed before reading", s -> injectIdentifiers(s)),
            new Case("Quotes, commas, Greek, CJK and emoji in answers", "works", s -> s.replace("the ", "the \"\"σ, 応力\"\" 😀 ")),
            new Case("One TA marked everything", "refuses: nothing to compare", s -> s.replaceAll(",Grader [A-D],", ",Grader A,")));
        for (Case c : cases) attempt(c, base, c.change().apply(base.csv), baseSev, md, rnd);

        // Arrangements the audit must handle, on real marks.
        int questions = 0;
        for (int qi = allQuestions ? 0 : 1; qi < data.questions().size(); qi += allQuestions ? 1 : 2) questions++;
        int[] perQ = new int[questions];
        for (int i = 0; i < questions; i++) perQ[i] = i % 2;
        Deal qw = deal(real, data, new SplittableRandom(5), 1, perQ);
        attempt(new Case("One TA per question", "cannot compare; explains why, no false correction", s -> s), qw, qw.csv, null, md, rnd);

        // Load: the course repeated under new roll numbers, timed.
        Deal big = deal(real, data, new SplittableRandom(6), 10, null);
        attempt(new Case("Ten times the size, new roll numbers", "works within memory, severity close to truth", s -> s), big, big.csv, null, md, rnd);

        if (flags.containsKey("xlsx") && flags.containsKey("csv")) {
            byte[] x = Files.readAllBytes(Path.of(flags.get("xlsx")));
            String fromXlsx = XlsxReader.toCsv(x);
            List<String> assessments = List.of("Assignment 1,", "Assignment 2,", "Assignment 3,");
            String csv = Files.readString(Path.of(flags.get("csv")), StandardCharsets.UTF_8);
            StringBuilder sub = new StringBuilder();
            String[] records = csv.split("\n(?=(?:KEY|UNT\\d+|roll_no),)");
            for (String r : records) {
                if (r.startsWith("roll_no") || assessments.stream().anyMatch(a -> r.contains("," + a))) sub.append(r).append('\n');
            }
            Map<String, Double> csvSev = new LinkedHashMap<>();
            Deal none = new Deal("", List.of(), List.of("Course TA", "Second grader"), new double[2], new double[0], new double[0]);
            attempt(new Case("Excel workbook: its CSV twin", "reference", s -> s), none, sub.toString(), csvSev, md, rnd);
            attempt(new Case("Excel workbook", "same as its CSV twin", s -> s), none, fromXlsx, csvSev, md, rnd);
        }
    }

    private static void attempt(Case c, Deal d, String text, Map<String, Double> baseline, StringBuilder md, Random unused) {
        long t0 = System.nanoTime();
        String row;
        try {
            Pseudonymiser p = CsvIngest.pseudonymiserFor(text);
            Pipeline.Run r = Pipeline.run(text, p, CourseConfig.defaults("robustness"), Pipeline.Options.defaults(), Pipeline.Progress.none());
            Map<String, String> labels = labels(p);
            double shift = Double.NaN;
            StringBuilder sev = new StringBuilder();
            if (r.model().isPresent()) {
                List<String> gl = r.ingest().design().graderLabels();
                shift = 0;
                for (int g = 0; g < d.taNames.size(); g++) {
                    int j = gl.indexOf(labels.get(d.taNames.get(g)));
                    if (j < 0) continue;
                    double s = r.model().get().severity(j);
                    sev.append(String.format(Locale.ROOT, "%s %+.2f ", d.taNames.get(g), s));
                    if (baseline != null) {
                        Double was = baseline.putIfAbsent(d.taNames.get(g), s);
                        if (was != null) shift = Math.max(shift, Math.abs(s - was));
                    }
                }
                if (d.truthSeverity.length > 0 && baseline == null) {
                    double err = 0;
                    for (int g = 0; g < d.taNames.size(); g++) {
                        int j = gl.indexOf(labels.get(d.taNames.get(g)));
                        if (j >= 0) err = Math.max(err, Math.abs(r.model().get().severity(j) - d.truthSeverity[g]));
                    }
                    sev.append(String.format(Locale.ROOT, "(largest error vs truth %.2f) ", err));
                }
            }
            long errors = r.ingest().diagnostics().stream().filter(x -> x.toString().toLowerCase(Locale.ROOT).contains("error")).count();
            String leak = "";
            if (c.name().startsWith("Roll numbers")) {
                long leaks = r.ingest().answers().stream().filter(a -> a.contains("UNT0") || a.contains("Grader A") || a.contains("CE23B092") || a.contains("someone@iitm.ac.in")).count();
                leak = "identifiers left in answers: " + leaks + "; ";
            }
            String list = r.outcome().map(o -> o.shortlist() instanceof Outcome.Emitted e ? e.rows().size() + " scripts" : "refused: " + ((Outcome.Refused) o.shortlist()).reason().split("[.(]")[0]).orElse("none");
            String answers = r.content().map(x -> x.status().name() + String.format(Locale.ROOT, " %.0f%%", 100 * x.textCoverage())).orElse("none");
            String grades = r.outcome().map(o -> String.valueOf(o.changed())).orElse("-");
            row = String.format(Locale.ROOT, "| %s | %s | yes | %d | %s | %s | %s | %s | %s | %s%s%.0f s; %d issue(s) reported |",
                    c.name(), c.expected(), r.ingest().responses().size(), r.verdict().getClass().getSimpleName(), answers,
                    Double.isNaN(shift) ? "-" : String.format(Locale.ROOT, "%.2f", shift), grades, list, leak, sev,
                    (System.nanoTime() - t0) / 1e9, r.ingest().diagnostics().size());
            if (errors < 0) throw new IllegalStateException();
        } catch (Exception | OutOfMemoryError e) {
            String msg = String.valueOf(e.getMessage()).split("\n")[0];
            // A refusal with a reason is the product working; anything else stopped it.
            String how = e instanceof IllegalArgumentException ? "refused, with a reason" : "**stopped**";
            row = String.format(Locale.ROOT, "| %s | %s | %s | - | - | - | - | - | - | %s: %s |", c.name(), c.expected(), how,
                    e.getClass().getSimpleName(), msg.substring(0, Math.min(170, msg.length())).replace("|", "/"));
        }
        System.out.println(row);
        md.append(row).append('\n');
    }

    /** The file's records: a record starts at a roll number and may span lines inside its quoted answer. */
    private static List<String> records(String csv) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (ch == '"') quoted = !quoted;
            if (ch == '\n' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String join(List<String> recs) {
        return String.join("\n", recs) + "\n";
    }

    private static String shuffled(String s, Random r) {
        List<String> recs = records(s);
        List<String> body = new ArrayList<>(recs.subList(1, recs.size()));
        Collections.shuffle(body, r);
        body.add(0, recs.get(0));
        return join(body);
    }

    private static String dropLines(String s, java.util.function.Predicate<String> drop) {
        List<String> keep = new ArrayList<>();
        for (String l : records(s)) if (!drop.test(l)) keep.add(l);
        return join(keep);
    }

    private static boolean isMarkRow(String l) {
        return !l.startsWith("roll_no") && !l.startsWith("KEY,");
    }

    private static String blankAnswers(String s, double share, Random r) {
        List<String> out = new ArrayList<>();
        for (String l : records(s)) {
            if (isMarkRow(l) && r.nextDouble() < share) {
                out.add(l.substring(0, l.indexOf(",\"")) + ",");
            } else {
                out.add(l);
            }
        }
        return join(out);
    }

    private static String dropAnswerColumn(String s) {
        List<String> out = new ArrayList<>();
        for (String l : records(s)) {
            if (l.startsWith("KEY,")) continue;
            if (l.startsWith("roll_no")) out.add(l.substring(0, l.lastIndexOf(',')));
            else out.add(l.substring(0, l.indexOf(",\"")));
        }
        return join(out);
    }

    /** Replaces the marks field (the fifth) of a share of the rows. */
    private static String mapMarks(String s, double share, String value, Random r) {
        List<String> out = new ArrayList<>();
        for (String l : records(s)) {
            if (isMarkRow(l) && r.nextDouble() < share) {
                String[] f = l.split(",", 6);
                out.add(f[0] + "," + f[1] + "," + f[2] + "," + f[3] + "," + value + "," + f[5]);
            } else {
                out.add(l);
            }
        }
        return join(out);
    }

    private static String injectIdentifiers(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (String l : records(s)) {
            if (isMarkRow(l) && i++ % 5 == 0) {
                String roll = l.substring(0, l.indexOf(','));
                int at = l.indexOf(",\"") + 2;
                out.add(l.substring(0, at) + roll + " CE23B092 someone@iitm.ac.in, as Grader A said: " + l.substring(at));
            } else {
                out.add(l);
            }
        }
        return join(out);
    }

    private EndToEndValidation() {
    }
}
