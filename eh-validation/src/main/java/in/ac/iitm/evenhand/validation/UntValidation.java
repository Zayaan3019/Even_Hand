package in.ac.iitm.evenhand.validation;

import in.ac.iitm.evenhand.content.ContentAnalysis;
import in.ac.iitm.evenhand.content.ContentFit;
import in.ac.iitm.evenhand.content.ContentResult;
import in.ac.iitm.evenhand.content.ContentSettings;
import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;
import in.ac.iitm.evenhand.design.IdentifiabilityAudit;
import in.ac.iitm.evenhand.embed.Embedders;
import in.ac.iitm.evenhand.estimate.SeverityEstimator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.outcome.Composite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;

/**
 * Validation on real, independently double-graded answers (the UNT dataset, see {@link UntDataset}).
 *
 * <p>Every answer was graded by two people, so how differently they graded is known. Each scenario
 * deals every <em>script</em> (one student's answers to one assignment) to one grader, the way an
 * IITM course splits its scripts, keeps only that grader's marks, and asks Even Hand to recover the
 * difference it can no longer see directly. Over many random deals that measures, for the estimate
 * Even Hand actually uses (marks-only or answer-anchored, chosen by the Hausman test), the error,
 * interval coverage, false alarms and power, on real text and real grading behaviour:
 *
 * <ul>
 *   <li><b>random</b>: scripts dealt at random between the two real graders.</li>
 *   <li><b>null</b>, <b>null-unfair</b>: both "TAs" carry the second grader's marks, so the truth is
 *       zero; any difference found is a false alarm. Unfair piles are dealt on the course TA's marks,
 *       which track true quality but are independent of the second grader's errors.</li>
 *   <li><b>planted-unfair</b>: one grader's marks, B made 10 points harsher, unfair piles.</li>
 *   <li><b>four</b>, <b>four-unfair</b>: four TAs with known offsets.</li>
 *   <li><b>slips</b>: 2% of marks replaced by an obvious slip; does the out-of-line check find them?</li>
 * </ul>
 *
 * <p>Tuning and testing use disjoint questions: the even-numbered half for tuning, every reported
 * figure on the odd-numbered half.
 *
 * <pre>java -cp ... in.ac.iitm.evenhand.validation.UntValidation &lt;dataset dir&gt; [--runs 100] [--tune] [--out dir]</pre>
 */
public final class UntValidation {

    /** One TA in a scenario: which real grader's marks they carry, plus a planted offset in points. */
    record Ta(String name, boolean usesTa, double offsetPoints) {}

    record Scenario(String name, String story, List<Ta> tas, boolean unfair, double slipRate) {}

    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("random", "the two real graders, scripts dealt at random",
                    List.of(new Ta("TA", true, 0), new Ta("Second", false, 0)), false, 0),
            new Scenario("null", "one real grader's marks under two labels, random deal (truth: no difference)",
                    List.of(new Ta("A", false, 0), new Ta("B", false, 0)), false, 0),
            new Scenario("null-unfair", "one real grader's marks under two labels, stronger scripts mostly to A "
                    + "(truth: no difference)", List.of(new Ta("A", false, 0), new Ta("B", false, 0)), true, 0),
            new Scenario("planted-unfair", "one real grader's marks, B 10 points harsher, stronger scripts mostly to A",
                    List.of(new Ta("A", false, 0), new Ta("B", false, -10)), true, 0),
            new Scenario("four", "the two real graders and a 10-point harsher copy of each, random deal",
                    List.of(new Ta("TA", true, 0), new Ta("Second", false, 0), new Ta("TA-10", true, -10),
                            new Ta("Second-10", false, -10)), false, 0),
            new Scenario("four-unfair", "one real grader's marks with offsets +5, 0, -5, -10; stronger scripts mostly "
                    + "to the first two", List.of(new Ta("+5", false, 5), new Ta("0", false, 0), new Ta("-5", false, -5),
                    new Ta("-10", false, -10)), true, 0),
            new Scenario("slips", "the two real graders, random deal, 2% of marks replaced by an obvious slip",
                    List.of(new Ta("TA", true, 0), new Ta("Second", false, 0)), false, 0.02));

    /** Per scenario, over all runs. */
    static final class Tally {
        int runs, answersChosen, rejected, placeboRejected, covered, intervals, count;
        double sseNaive, sseMarks, sseAnswers, sseFinal, signal, truthFirst, biasFinal;
        int slipsPlanted, slipsFound, flags, flagsRight;

        String row(String name) {
            return String.format(Locale.ROOT,
                    "| %s | %d | %+.1f | %.2f | %.2f | %.2f | %.2f | **%.2f** | %+.2f | %.0f%% | %.0f%% | %.0f%% | %.0f%% | %s |",
                    name, runs, truthFirst / runs, signal / runs, rmse(sseNaive), rmse(sseMarks), rmse(sseAnswers),
                    rmse(sseFinal), biasFinal / runs, 100.0 * answersChosen / runs, 100.0 * covered / Math.max(1, intervals),
                    100.0 * rejected / runs, 100.0 * placeboRejected / runs,
                    slipsPlanted == 0 ? "-" : String.format(Locale.ROOT, "%d of %d found; %d of %d flags were slips",
                            slipsFound, slipsPlanted, flagsRight, flags));
        }

        double rmse(double sse) {
            return Math.sqrt(sse / Math.max(1, count));
        }
    }

    static final String HEADER = "| Scenario | Runs | True severity, first TA | Content signal | RMSE pile averages | "
            + "RMSE marks-only | RMSE answers | **RMSE Even Hand** | Bias, first TA | Answers' estimate chosen | "
            + "95% interval coverage | Differences found (p < 0.05) | Placebo flagged | Slips |\n"
            + "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n";

    private final UntDataset data;
    private final Map<String, float[]> vectors;
    private final boolean semantic;
    private final String embedder;

    private UntValidation(UntDataset data, Map<String, float[]> vectors, boolean semantic, String embedder) {
        this.data = data;
        this.vectors = vectors;
        this.semantic = semantic;
        this.embedder = embedder;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: UntValidation <dataset dir> [--runs 100] [--tune] [--out dir]");
            System.exit(2);
        }
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--tune")) flags.put("tune", "1");
            else if (args[i].startsWith("--") && i + 1 < args.length) flags.put(args[i].substring(2), args[++i]);
        }
        UntDataset data = UntDataset.load(Path.of(args[0]));
        Embedders.Choice choice = Embedders.best();
        System.out.println("dataset: " + data.questions().size() + " questions, " + data.answerCount()
                + " answers; embedder: " + choice.embedder().id() + " (" + choice.note() + ")");
        List<String> texts = new ArrayList<>();
        for (UntDataset.Question q : data.questions()) {
            for (UntDataset.Answer a : q.answers()) texts.add(a.text());
            if (!q.key().isEmpty()) texts.add(q.key());
        }
        long t0 = System.nanoTime();
        float[][] v = choice.embedder().embed(texts);
        System.out.printf(Locale.ROOT, "embedded %d answers in %.1f s%n", texts.size(), (System.nanoTime() - t0) / 1e9);
        Map<String, float[]> byText = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) byText.put(texts.get(i), v[i]);
        UntValidation val = new UntValidation(data, byText, choice.embedder().semantic(), choice.embedder().id());

        int runs = Integer.parseInt(flags.getOrDefault("runs", "100"));
        ContentSettings settings = ContentSettings.defaults();
        StringBuilder tuning = new StringBuilder();
        if (flags.containsKey("tune")) settings = val.tune(Math.max(20, runs / 3), tuning);

        IntPredicate test = q -> q % 2 == 1;
        Map<String, Tally> withKey = new LinkedHashMap<>();
        Map<String, Tally> withoutKey = new LinkedHashMap<>();
        for (Scenario sc : SCENARIOS) {
            Tally t = val.evaluate(sc, settings, runs, test, 777, true);
            withKey.put(sc.name(), t);
            System.out.println("with key    " + t.row(sc.name()));
            Tally n = val.evaluate(sc, settings, runs, test, 777, false);
            withoutKey.put(sc.name(), n);
            System.out.println("without key " + n.row(sc.name()));
        }
        if (flags.containsKey("out")) {
            Path out = Path.of(flags.get("out"));
            Files.createDirectories(out);
            Files.writeString(out.resolve("VALIDATION-REAL.md"), val.markdown(withKey, withoutKey, settings, runs, tuning),
                    StandardCharsets.UTF_8);
            System.out.println("wrote " + out.resolve("VALIDATION-REAL.md"));
        }
    }

    // ------------------------------------------------------------------ tuning on the even half

    ContentSettings tune(int runs, StringBuilder log) {
        IntPredicate train = q -> q % 2 == 0;
        ContentSettings best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        log.append("| Neighbours | Caliper | RMSE random | RMSE planted-unfair | RMSE four-unfair | False alarms null-unfair | Score |\n");
        log.append("|---|---|---|---|---|---|---|\n");
        for (int k : new int[] {1, 2, 3}) {
            for (double cal : new double[] {0.4, 0.5, 0.6, 0.7}) {
                ContentSettings s = ContentSettings.defaults().withMatching(k, cal, ContentSettings.defaults().lexicalCaliper())
                        .withDraws(100);
                Tally r = evaluate(SCENARIOS.get(0), s, runs, train, 11, true);
                Tally u = evaluate(SCENARIOS.get(3), s, runs, train, 12, true);
                Tally f = evaluate(SCENARIOS.get(5), s, runs, train, 13, true);
                Tally n = evaluate(SCENARIOS.get(2), s, runs, train, 14, true);
                double a = r.rmse(r.sseFinal), b = u.rmse(u.sseFinal), c = f.rmse(f.sseFinal);
                double alarms = n.rejected / (double) n.runs;
                double score = (a + b + c) / 3 + 20 * Math.max(0, alarms - 0.05);
                log.append(String.format(Locale.ROOT, "| %d | %.2f | %.2f | %.2f | %.2f | %.0f%% | %.2f |%n",
                        k, cal, a, b, c, 100 * alarms, score));
                System.out.printf(Locale.ROOT, "tune k=%d caliper=%.2f -> %.2f %.2f %.2f alarms %.0f%% score %.2f%n",
                        k, cal, a, b, c, 100 * alarms, score);
                if (score < bestScore) {
                    bestScore = score;
                    best = ContentSettings.defaults().withMatching(k, cal, ContentSettings.defaults().lexicalCaliper());
                }
            }
        }
        log.append(String.format(Locale.ROOT, "%nChosen: %d neighbour(s), caliper %.2f.%n", best.neighbours(), best.caliper()));
        return best;
    }

    // ------------------------------------------------------------------ one scenario, many deals

    Tally evaluate(Scenario sc, ContentSettings settings, int runs, IntPredicate questionFilter, long seed, boolean key) {
        Tally t = new Tally();
        Settings marksSettings = Settings.defaults().withDraws(100, 0);
        for (int run = 0; run < runs; run++) {
            SplittableRandom rng = new SplittableRandom(seed * 1_000_003L + run * 7919L + sc.name().hashCode());
            Course c = course(sc, questionFilter, rng);
            ContentAnalysis.Input input = c.input(semantic, embedder, key);
            MarkingDesign design = input.design();
            ContentResult r = ContentAnalysis.analyse(input, settings.withSeed(seed + run));

            double[] w = new double[] {1};
            AuditVerdict verdict = IdentifiabilityAudit.audit(design, new Composite(c.responses, design, w).questionWeights());
            Optional<FittedModel> marks = verdict.permitsEstimation()
                    ? Optional.of(SeverityEstimator.fit(AnchoredDesign.of(design, verdict, Anchoring.standard()),
                            c.responses, marksSettings.withSeed(seed + run)))
                    : Optional.empty();
            ContentFit.Choice choice = ContentFit.choose(verdict, marks, r);
            boolean answers = choice.useAnswers() || marks.isEmpty();
            if (!answers && Double.isFinite(choice.pValue())) {
                marks = Optional.of(ContentFit.withAllowance(marks.get(), settings.margin()));   // as the pipeline does
            }
            Optional<FittedModel> chosenMarks = marks;

            t.runs++;
            if (answers) t.answersChosen++;
            t.signal += r.signal();
            t.truthFirst += c.truth[0];
            if (r.pValue() < 0.05) t.rejected++;
            if (r.causal().map(f -> f.placebo().pValue() < 0.05).orElse(false)) t.placeboRejected++;
            double[] naive = c.naivePileGap();
            for (int g = 0; g < sc.tas().size(); g++) {
                double truth = c.truth[g];
                double fromAnswers = r.severity()[g];
                final int gg = g;
                double fromMarks = chosenMarks.map(m -> m.severity(gg)).orElse(fromAnswers);
                double chosen = answers ? fromAnswers : fromMarks;
                double[] ci = answers ? r.interval95(g) : chosenMarks.get().interval95(g);
                t.sseNaive += sq(naive[g] - truth);
                t.sseMarks += sq(fromMarks - truth);
                t.sseAnswers += sq(fromAnswers - truth);
                t.sseFinal += sq(chosen - truth);
                if (g == 0) t.biasFinal += chosen - truth;
                if (ci[0] <= truth && truth <= ci[1]) t.covered++;
                t.intervals++;
                t.count++;
            }
            if (sc.slipRate() > 0) {
                t.slipsPlanted += c.slips.size();
                for (ContentResult.OutOfLine o : r.outOfLine()) {
                    t.flags++;
                    if (c.slips.contains(o.student() + "|" + o.question())) t.flagsRight++;
                }
                for (String s : c.slips) {
                    if (r.outOfLine().stream().anyMatch(o -> (o.student() + "|" + o.question()).equals(s))) t.slipsFound++;
                }
            }
        }
        return t;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** One deal of the real scripts: who got which, and the truth it implies. */
    final class Course {
        final List<Response> responses = new ArrayList<>();
        final List<String> answers = new ArrayList<>();
        final List<float[]> vecs = new ArrayList<>();
        final List<String> studentLabels = new ArrayList<>();
        final List<String> questionLabels = new ArrayList<>();
        final List<String> graderLabels = new ArrayList<>();
        final double[] truth;
        final List<String> slips = new ArrayList<>();
        final Map<Integer, float[]> keys = new LinkedHashMap<>();

        Course(int graders) {
            truth = new double[graders];
        }

        ContentAnalysis.Input input(boolean semantic, String embedder, boolean withKey) {
            MarkingDesign design = MarkingDesign.fromResponses(responses, studentLabels, questionLabels, graderLabels);
            return new ContentAnalysis.Input(responses, answers, vecs.toArray(new float[0][]), design, embedder, semantic,
                    withKey ? keys : Map.of());
        }

        /** What comparing pile averages question by question would say: the obvious method. */
        double[] naivePileGap() {
            int g = truth.length;
            double[] sum = new double[g], w = new double[g];
            Map<Integer, double[]> qMean = new LinkedHashMap<>();
            for (Response r : responses) {
                qMean.computeIfAbsent(r.question(), k -> new double[2]);
                qMean.get(r.question())[0] += 100.0 * r.score() / r.maxScore();
                qMean.get(r.question())[1]++;
            }
            for (Response r : responses) {
                double[] m = qMean.get(r.question());
                sum[r.grader()] += r.maxMarks() * (m[0] / m[1] - 100.0 * r.score() / r.maxScore());
                w[r.grader()] += r.maxMarks();
            }
            double[] s = new double[g];
            double bar = 0;
            for (int j = 0; j < g; j++) {
                s[j] = w[j] == 0 ? 0 : sum[j] / w[j];
                bar += s[j] / g;
            }
            for (int j = 0; j < g; j++) s[j] -= bar;
            return s;
        }
    }

    /**
     * Deals scripts: a script is one student's answers to one assignment (the dataset's answer index
     * within an assignment), marked whole by one TA. Unfair deals give the stronger scripts (by the course
     * TA's own marks, relative to the assignment's median) mostly to the first half of the TAs.
     */
    Course course(Scenario sc, IntPredicate questionFilter, SplittableRandom rng) {
        int graders = sc.tas().size();
        Course c = new Course(graders);
        for (Ta ta : sc.tas()) c.graderLabels.add(ta.name());
        Map<String, double[]> scriptQuality = new LinkedHashMap<>();
        List<Integer> selected = new ArrayList<>();
        for (int qi = 0; qi < data.questions().size(); qi++) {
            if (!questionFilter.test(qi)) continue;
            selected.add(qi);
            UntDataset.Question q = data.questions().get(qi);
            for (int z = 0; z < q.answers().size(); z++) {
                double[] s = scriptQuality.computeIfAbsent(script(q, z), k -> new double[2]);
                s[0] += 100.0 * q.answers().get(z).ta() / q.maxMarks();
                s[1]++;
            }
        }
        Map<String, List<Double>> byAssignment = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : scriptQuality.entrySet()) {
            byAssignment.computeIfAbsent(assignmentOf(e.getKey()), k -> new ArrayList<>()).add(e.getValue()[0] / e.getValue()[1]);
        }
        Map<String, Integer> dealt = new LinkedHashMap<>();
        Map<String, Integer> studentIndex = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : scriptQuality.entrySet()) {
            double quality = e.getValue()[0] / e.getValue()[1];
            double median = median(byAssignment.get(assignmentOf(e.getKey())).stream().mapToDouble(Double::doubleValue).toArray());
            boolean strong = quality > median || (quality == median && rng.nextBoolean());
            dealt.put(e.getKey(), allocate(sc, strong, rng));
            studentIndex.put(e.getKey(), studentIndex.size());
            c.studentLabels.add("S-" + e.getKey());
        }

        double[] meanPct = new double[graders];
        double wTotal = 0;
        int qIndex = 0;
        for (int qi : selected) {
            UntDataset.Question q = data.questions().get(qi);
            c.questionLabels.add(q.id());
            if (!q.key().isEmpty()) c.keys.put(qIndex, vectors.get(q.key()));
            for (int z = 0; z < q.answers().size(); z++) {
                UntDataset.Answer a = q.answers().get(z);
                for (int g = 0; g < graders; g++) meanPct[g] += 100.0 * mark(sc.tas().get(g), a, q);
                wTotal += q.maxMarks();
                String script = script(q, z);
                int g = dealt.get(script);
                double m = mark(sc.tas().get(g), a, q);
                if (sc.slipRate() > 0 && rng.nextDouble() < sc.slipRate()) {
                    m = m >= q.maxMarks() / 2 ? 0 : q.maxMarks();
                    c.slips.add("S-" + script + "|" + q.id());
                }
                int steps = (int) Math.round(m * 2);
                c.responses.add(new Response(new DesignCell(studentIndex.get(script), qIndex, g, 0, DesignCell.NO_POSITION, true),
                        steps, (int) Math.round(q.maxMarks() * 2), 2));
                c.answers.add(a.text());
                c.vecs.add(vectors.get(a.text()));
            }
            qIndex++;
        }
        double bar = 0;
        for (int g = 0; g < graders; g++) {
            meanPct[g] /= wTotal;
            bar += meanPct[g] / graders;
        }
        for (int g = 0; g < graders; g++) c.truth[g] = bar - meanPct[g];   // harsh = gives less than the panel
        return c;
    }

    private static String script(UntDataset.Question q, int z) {
        return q.id().substring(0, q.id().indexOf('.')) + "-" + z;
    }

    private static String assignmentOf(String script) {
        return script.substring(0, script.indexOf('-'));
    }

    /** Random, or unfair: strong scripts go to the first half of the TAs with probability 0.8. */
    private static int allocate(Scenario sc, boolean strong, SplittableRandom rng) {
        int g = sc.tas().size();
        if (!sc.unfair()) return rng.nextInt(g);
        int half = Math.max(1, g / 2);
        boolean first = rng.nextDouble() < (strong ? 0.8 : 0.2);
        return first ? rng.nextInt(half) : half + rng.nextInt(g - half);
    }

    /** The TA's mark for an answer: their grader's, shifted by the planted offset, in half marks. */
    private static double mark(Ta ta, UntDataset.Answer a, UntDataset.Question q) {
        double base = ta.usesTa() ? a.ta() : a.second();
        double shifted = base + ta.offsetPoints() / 100.0 * q.maxMarks();
        return Math.max(0, Math.min(q.maxMarks(), Math.round(shifted * 2) / 2.0));
    }

    private static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n == 0 ? 0 : n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }

    // ------------------------------------------------------------------ the write-up

    String markdown(Map<String, Tally> withKey, Map<String, Tally> withoutKey, ContentSettings s, int runs,
                    StringBuilder tuning) {
        StringBuilder b = new StringBuilder();
        b.append("Generated by `eh-validation`'s `UntValidation` on the UNT Computer Science Short Answer Dataset v2.0 ")
         .append("(Mohler, Bunescu and Mihalcea, ACL 2011): ").append(data.answerCount())
         .append(" real student answers to ").append(data.questions().size())
         .append(" questions, every one graded independently by the course TA and by a second grader. Answers read by ")
         .append(embedder).append(". ").append(runs).append(" deals per scenario, on the odd-numbered questions; ")
         .append("settings were chosen on the even-numbered ones. Severity in percentage points of a question's maximum; ")
         .append("RMSE over every TA in every run.\n\n");
        b.append("**With the instructor's answer key:**\n\n").append(HEADER);
        for (Map.Entry<String, Tally> e : withKey.entrySet()) b.append(e.getValue().row(e.getKey())).append('\n');
        b.append("\n**Without an answer key:**\n\n").append(HEADER);
        for (Map.Entry<String, Tally> e : withoutKey.entrySet()) b.append(e.getValue().row(e.getKey())).append('\n');
        b.append("\n");
        for (Scenario sc : SCENARIOS) b.append("- **").append(sc.name()).append("**: ").append(sc.story()).append(".\n");
        b.append(String.format(Locale.ROOT, "%nSettings: %d neighbour(s) per answer, caliper %.2f, margin %.1f points, "
                + "%d bootstrap draws.%n", s.neighbours(), s.caliper(), s.margin(), s.bootstrapDraws()));
        if (!tuning.isEmpty()) b.append("\n**Tuning (even-numbered questions):**\n\n").append(tuning);
        return b.toString();
    }
}
