package in.ac.iitm.evenhand.content;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;
import in.ac.iitm.evenhand.numerics.Distributions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Stage 3b: were the marking differences explained by what the students wrote?
 *
 * <p>For every question with written answers:
 * <ol>
 *   <li><b>Content model</b> ({@link ContentModel}): what each answer is expected to earn from
 *       what it says, learned within each assistant's pile so it cannot absorb severity.</li>
 *   <li><b>Matching</b> ({@link Matching}): each answer is paired with the most similar answers
 *       marked by <em>other</em> assistants. Their mark gap, minus the content model's estimate
 *       of what the two answers say differently, is evidence about the two assistants alone:
 *       the bias-corrected matching estimator of Abadie and Imbens (2011).</li>
 *   <li><b>Severities</b> ({@link SeverityGraph}): least squares over all pairs, per group of
 *       assistants the answers link, centred on the group.</li>
 *   <li><b>Uncertainty</b>: a multiplier bootstrap that reweights whole students, so an
 *       interval reflects which students happened to be in which pile; the p-value is the share
 *       of recentred draws at least as spread as the estimate.</li>
 * </ol>
 * and then the causal checks of {@link CausalFindings} and the evidence an instructor can read:
 * near-identical answers given different marks, and marks out of line with similar answers.
 *
 * <p>Nothing here changes a mark. It reads pseudonymised labels only; the answer text it quotes
 * has already had identifiers removed by ingest.
 */
public final class ContentAnalysis {

    /**
     * @param responses every response, as ingest produced them
     * @param answers   written answer per response ("" where none)
     * @param vectors   unit embedding per response (null where no answer)
     * @param embedder  what produced the vectors
     * @param semantic  a language model (true) or the lexical fallback
     * @param references the instructor's answer key, embedded, by question index (may be empty)
     */
    public record Input(List<Response> responses, List<String> answers, float[][] vectors,
                        MarkingDesign design, String embedder, boolean semantic, Map<Integer, float[]> references) {
        public Input {
            if (answers.size() != responses.size() || vectors.length != responses.size()) {
                throw new IllegalArgumentException("one answer and one vector (or null) per response");
            }
            references = Map.copyOf(references);
        }

        public Input(List<Response> responses, List<String> answers, float[][] vectors, MarkingDesign design,
                     String embedder, boolean semantic) {
            this(responses, answers, vectors, design, embedder, semantic, Map.of());
        }
    }

    static final int MIN_ANSWERS_PER_QUESTION = 8;
    static final int MIN_TEXTS = 20;
    static final double MIN_COVERAGE = 0.3;
    static final int EXCERPT_CHARS = 320;
    static final int SHOWN_PAIRS = 12;
    static final int SHOWN_INCONSISTENT = 8;
    static final int SHOWN_OUT_OF_LINE = 20;
    static final int PLACEBO_SHUFFLES = 99;

    private ContentAnalysis() {
    }

    public static ContentResult analyse(Input in, ContentSettings s) {
        MarkingDesign design = in.design();
        int graders = design.graderCount();
        List<String> labels = design.graderLabels();
        int texts = 0;
        for (int i = 0; i < in.responses().size(); i++) {
            if (in.vectors()[i] != null && !in.answers().get(i).isBlank()) texts++;
        }
        double coverage = in.responses().isEmpty() ? 0 : texts / (double) in.responses().size();
        List<String> notes = new ArrayList<>();
        if (!in.semantic()) {
            notes.add("Answers were compared by their wording only (no language model is installed), so two "
                    + "answers making the same point in different words are not recognised as alike.");
        }
        if (texts < MIN_TEXTS || coverage < MIN_COVERAGE) {
            notes.add(texts == 0 ? "The file carries no written answers."
                    : "Only " + texts + " responses (" + pct(coverage) + ") carry written answers: too few to compare.");
            return empty(ContentResult.Status.NO_TEXT, in, coverage, labels, notes);
        }

        double caliper = in.semantic() ? s.caliper() : s.lexicalCaliper();
        List<Matching> questions = questionsOf(in, s, caliper, notes);
        if (questions.isEmpty()) {
            notes.add("No question has " + MIN_ANSWERS_PER_QUESTION + " or more written answers.");
            return empty(ContentResult.Status.NO_TEXT, in, coverage, labels, notes);
        }

        List<Matching.Pair> pairs = new ArrayList<>();
        for (Matching q : questions) pairs.addAll(q.pairs(q.grader, s.neighbours()));
        List<List<Integer>> groups = SeverityGraph.groups(pairs, graders, s.minLinkPairs());
        double[] severity = SeverityGraph.solve(pairs, groups, graders, s.severityShrinkage(), null);

        int students = design.studentCount();
        double[][] draws = new double[s.bootstrapDraws()][];
        IntStream.range(0, s.bootstrapDraws()).parallel().forEach(b -> {
            SplittableRandom rng = new SplittableRandom(s.seed() * 1_000_003L + b);
            double[] xi = new double[students];
            for (int k = 0; k < students; k++) xi[k] = -Math.log(1 - rng.nextDouble());   // Exp(1)
            draws[b] = SeverityGraph.solve(pairs, groups, graders, s.severityShrinkage(), xi);
        });
        double observed = SeverityGraph.spread(severity, groups);
        double pValue = recentredP(draws, severity, groups, observed, s.margin());
        double[] sampling = standardErrors(draws, graders);
        biasAware(draws, severity, s.margin());
        double[] se = standardErrors(draws, graders);

        int[] pairCount = new int[graders];
        for (Matching.Pair p : pairs) {
            pairCount[p.ga()]++;
            pairCount[p.gb()]++;
        }
        List<ContentResult.QuestionSummary> summaries = new ArrayList<>();
        double sse = 0, withinSs = 0;
        int answered = 0, matched = 0;
        for (Matching q : questions) {
            int qPairs = 0;
            boolean[] has = new boolean[q.size()];
            for (Matching.Pair p : pairs) {
                if (p.q() != q) continue;
                qPairs++;
                has[p.i()] = true;
            }
            int assistants = (int) Arrays.stream(q.grader).distinct().count();
            summaries.add(new ContentResult.QuestionSummary(q.label, q.size(), q.model.signal, qPairs, assistants));
            if (qPairs > 0) {
                sse += q.model.sse;
                withinSs += q.model.withinSs;
            }
            answered += q.size();
            for (boolean h : has) if (h) matched++;
        }
        // Pooled over questions: small questions' noisy R^2 values are not averaged, their sums are added.
        double signal = withinSs <= 0 ? 0 : 1 - sse / withinSs;
        double matchedShare = answered == 0 ? 0 : matched / (double) answered;

        ContentResult.Status status;
        if (pairs.isEmpty()) {
            status = ContentResult.Status.NO_LINKS;
            notes.add("No answer marked by one assistant closely resembles an answer marked by another, so the "
                    + "answers cannot compare the assistants. This is what question-wise marking looks like: every "
                    + "answer to a question was marked by the same assistant.");
        } else if (signal < s.minSignal()) {
            status = ContentResult.Status.WEAK_SIGNAL;
            notes.add(String.format(Locale.ROOT, "What the answers say explains only %.0f%% of how marks vary within "
                    + "an assistant's pile (at least %.0f%% is needed), so they are not used to compare assistants. "
                    + "The near-identical answers below are still worth a look.", 100 * signal, 100 * s.minSignal()));
        } else {
            status = ContentResult.Status.USABLE;
        }
        for (List<Integer> g : groups) {
            if (g.size() == 1 && graders > 1 && status != ContentResult.Status.NO_LINKS) {
                notes.add(labels.get(g.get(0)) + "'s answers have fewer than " + s.minLinkPairs()
                        + " close matches marked by anyone else, so they are not compared through the answers.");
            }
        }

        List<ContentResult.PairEvidence> sameDifferent = sameAnswerDifferentMarks(pairs, in, s);
        List<ContentResult.PairEvidence> inconsistent = sameAssistantInconsistent(questions, in, s);
        List<ContentResult.OutOfLine> outOfLine = status == ContentResult.Status.NO_TEXT ? List.of()
                : signal < s.minSignal() ? List.<ContentResult.OutOfLine>of() : outOfLine(questions, severity, in, s);

        Optional<CausalFindings> causal = pairs.isEmpty() ? Optional.empty()
                : Optional.of(causal(questions, pairs, groups, severity, se, draws, observed, outOfLine, in, s));

        return new ContentResult(status, in.embedder(), in.semantic(), coverage, summaries, labels, severity, se,
                sampling, pairCount, groups, draws, pValue, observed, signal, matchedShare, sameDifferent, inconsistent,
                outOfLine, causal, notes);
    }

    // ---------------------------------------------------------------- building the questions

    private static List<Matching> questionsOf(Input in, ContentSettings s, double caliper, List<String> notes) {
        Map<Integer, List<Integer>> byQuestion = new LinkedHashMap<>();
        for (int i = 0; i < in.responses().size(); i++) {
            if (in.vectors()[i] == null || in.answers().get(i).isBlank()) continue;
            byQuestion.computeIfAbsent(in.responses().get(i).question(), q -> new ArrayList<>()).add(i);
        }
        List<Matching> out = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : byQuestion.entrySet()) {
            List<Integer> idx = e.getValue();
            String label = in.design().questionLabels().get(e.getKey());
            if (idx.size() < MIN_ANSWERS_PER_QUESTION) {
                skipped.add(label);
                continue;
            }
            int n = idx.size();
            int[] resp = new int[n], g = new int[n], st = new int[n];
            float[][] x = new float[n][];
            double[] y = new double[n];
            String[] text = new String[n];
            for (int k = 0; k < n; k++) {
                Response r = in.responses().get(idx.get(k));
                resp[k] = idx.get(k);
                g[k] = r.grader();
                st[k] = r.student();
                x[k] = in.vectors()[idx.get(k)];
                y[k] = 100.0 * r.score() / r.maxScore();
                text[k] = in.answers().get(idx.get(k));
            }
            Response first = in.responses().get(idx.get(0));
            out.add(new Matching(e.getKey(), label, first.maxMarks(), resp, x, y, g, st, text));
        }
        // What every question shares first (key, consensus, length), then what each one's embedding adds.
        Map<Matching, double[]> offsets = out.isEmpty() ? Map.of()
                : PooledQuality.offsets(out, in.references(), s.folds(), s.seed());
        for (Matching m : out) {
            m.model = ContentModel.fit(m.x, m.y, m.grader, m.student, in.design().graderCount(), s.folds(), s.seed(),
                    offsets.get(m));
            m.findNeighbours(caliper);
        }
        if (!in.references().isEmpty()) {
            notes.add("The answer key was used for " + in.references().size() + " question(s): closeness to it is one "
                    + "of the signs of quality the comparison allows for.");
        }
        if (!skipped.isEmpty()) {
            notes.add("Not compared, too few written answers (fewer than " + MIN_ANSWERS_PER_QUESTION + "): "
                    + String.join(", ", skipped) + ".");
        }
        return out;
    }

    // ---------------------------------------------------------------- uncertainty

    private static double[] standardErrors(double[][] draws, int graders) {
        double[] se = new double[graders];
        if (draws.length < 2) return se;
        for (int j = 0; j < graders; j++) {
            double mean = 0;
            for (double[] d : draws) mean += d[j];
            mean /= draws.length;
            double ss = 0;
            for (double[] d : draws) ss += (d[j] - mean) * (d[j] - mean);
            se[j] = Math.sqrt(ss / (draws.length - 1));
        }
        return se;
    }

    /**
     * Widens each assistant's draws about the estimate so that their 95% interval grows by {@code allowance}
     * on each side: a bias-aware interval. Every interval, standard error and re-check probability
     * downstream then carries the allowance for what the answers' embedding cannot see.
     */
    static void biasAware(double[][] draws, double[] estimate, double allowance) {
        if (draws.length < 2 || allowance <= 0) return;
        for (int j = 0; j < estimate.length; j++) {
            double[] c = new double[draws.length];
            for (int b = 0; b < c.length; b++) c[b] = draws[b][j];
            Arrays.sort(c);
            double lo = c[(int) Math.floor(0.025 * (c.length - 1))], hi = c[(int) Math.ceil(0.975 * (c.length - 1))];
            double half = Math.max(1e-6, (hi - lo) / 2);
            double factor = (half + allowance) / half;
            for (double[] d : draws) d[j] = estimate[j] + (d[j] - estimate[j]) * factor;
        }
    }

    /**
     * Share of draws, recentred on the estimate (a world where the assistants do not differ), whose spread
     * comes within {@code margin} of the estimate's: the test that the assistants differ by more than the
     * smallest difference worth reporting.
     */
    private static double recentredP(double[][] draws, double[] est, List<List<Integer>> groups, double observed,
                                     double margin) {
        if (draws.length == 0) return Double.NaN;
        int count = 0;
        for (double[] d : draws) {
            double[] c = new double[d.length];
            for (int j = 0; j < d.length; j++) c[j] = d[j] - est[j];
            if (SeverityGraph.spread(c, groups) >= observed - margin) count++;
        }
        return (1.0 + count) / (1.0 + draws.length);
    }

    // ---------------------------------------------------------------- evidence

    private static List<ContentResult.PairEvidence> sameAnswerDifferentMarks(List<Matching.Pair> pairs, Input in,
                                                                             ContentSettings s) {
        double threshold = in.semantic() ? s.evidenceSimilarity() : Math.min(0.95, s.lexicalCaliper() + 0.35);
        Set<String> seen = new HashSet<>();
        List<Matching.Pair> candidates = new ArrayList<>();
        for (Matching.Pair p : pairs) {
            if (p.similarity() < threshold || Math.abs(p.raw()) < s.evidenceGap()) continue;
            int a = Math.min(p.q().response[p.i()], p.q().response[p.j()]);
            int b = Math.max(p.q().response[p.i()], p.q().response[p.j()]);
            if (seen.add(a + ":" + b)) candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble((Matching.Pair p) -> -Math.abs(p.raw()) * p.similarity())
                .thenComparingInt(p -> p.q().response[p.i()]));
        List<ContentResult.PairEvidence> out = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Matching.Pair p : candidates) {
            int ra = p.q().response[p.i()], rb = p.q().response[p.j()];
            if (used.contains(ra) || used.contains(rb)) continue;   // one appearance per answer
            used.add(ra);
            used.add(rb);
            boolean firstHigher = p.raw() >= 0;
            int hi = firstHigher ? p.i() : p.j(), lo = firstHigher ? p.j() : p.i();
            out.add(new ContentResult.PairEvidence(p.q().label, p.q().maxMarks, p.similarity(), Math.abs(p.raw()),
                    side(p.q(), hi, in), side(p.q(), lo, in)));
            if (out.size() >= SHOWN_PAIRS) break;
        }
        return out;
    }

    private static List<ContentResult.PairEvidence> sameAssistantInconsistent(List<Matching> questions, Input in,
                                                                              ContentSettings s) {
        double threshold = in.semantic() ? Math.max(0.95, s.evidenceSimilarity() + 0.05)
                : Math.min(0.97, s.lexicalCaliper() + 0.45);
        double gap = 1.5 * s.evidenceGap();
        List<ContentResult.PairEvidence> all = new ArrayList<>();
        for (Matching q : questions) {
            for (int[] ij : q.sameAssistantPairs(threshold)) {
                int i = ij[0], j = ij[1];
                double d = q.y[i] - q.y[j];
                if (Math.abs(d) < gap || q.student[i] == q.student[j]) continue;
                int hi = d >= 0 ? i : j, lo = d >= 0 ? j : i;
                all.add(new ContentResult.PairEvidence(q.label, q.maxMarks, ij[2] / 1_000_000.0, Math.abs(d),
                        side(q, hi, in), side(q, lo, in)));
            }
        }
        all.sort(Comparator.comparingDouble((ContentResult.PairEvidence e) -> -e.gapPoints() * e.similarity()));
        return all.subList(0, Math.min(SHOWN_INCONSISTENT, all.size()));
    }

    private static List<ContentResult.OutOfLine> outOfLine(List<Matching> questions, double[] severity, Input in,
                                                           ContentSettings s) {
        List<ContentResult.OutOfLine> all = new ArrayList<>();
        for (Matching q : questions) {
            if (q.model.signal <= 0) continue;
            int n = q.size();
            double[] e = new double[n];
            for (int i = 0; i < n; i++) e[i] = q.y[i] - q.model.predicted[i];
            double sigma = 1.4826 * mad(e);
            if (!(sigma > 1e-9)) continue;
            for (int i = 0; i < n; i++) {
                double z = e[i] / sigma;
                if (Math.abs(z) < s.outlierZ()) continue;
                double sum = 0;
                int used = 0;
                for (int t = 0; t < q.neighbour[i].length && used < Math.max(3, s.neighbours()); t++) {
                    int j = q.neighbour[i][t];
                    if (q.student[j] == q.student[i]) continue;
                    sum += q.y[j] + severity[q.grader[j]] - severity[q.grader[i]];
                    used++;
                }
                if (used < 2) continue;
                double expected = Math.max(0, Math.min(100, sum / used));
                if (Math.abs(q.y[i] - expected) < s.outlierGap()) continue;
                Response r = in.responses().get(q.response[i]);
                all.add(new ContentResult.OutOfLine(q.label, q.maxMarks,
                        in.design().studentLabels().get(r.student()), in.design().graderLabels().get(r.grader()),
                        r.marks(), expected * q.maxMarks / 100.0, z, excerpt(q.text[i])));
            }
        }
        all.sort(Comparator.comparingDouble((ContentResult.OutOfLine o) -> -Math.abs(o.z())));
        return all.subList(0, Math.min(SHOWN_OUT_OF_LINE, all.size()));
    }

    private static ContentResult.Side side(Matching q, int i, Input in) {
        Response r = in.responses().get(q.response[i]);
        return new ContentResult.Side(in.design().studentLabels().get(r.student()),
                in.design().graderLabels().get(r.grader()), r.marks(), excerpt(q.text[i]));
    }

    static String excerpt(String text) {
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() <= EXCERPT_CHARS ? t : t.substring(0, EXCERPT_CHARS - 1).stripTrailing() + "…";
    }

    // ---------------------------------------------------------------- causal checks

    private static CausalFindings causal(List<Matching> questions, List<Matching.Pair> pairs,
                                         List<List<Integer>> groups, double[] severity, double[] se,
                                         double[][] draws, double observed, List<ContentResult.OutOfLine> outOfLine,
                                         Input in, ContentSettings s) {
        int graders = in.design().graderCount();
        List<String> labels = in.design().graderLabels();

        // 1. Decomposition and the variance shares, from deviations within each question.
        double[] wSum = new double[graders], raw = new double[graders], content = new double[graders];
        int[] answers = new int[graders];
        double total = 0, contentVar = 0, assistantVar = 0;
        // 2. Balance before matching needs each answer's content deviation.
        List<List<Double>> dev = new ArrayList<>();
        for (int g = 0; g < graders; g++) dev.add(new ArrayList<>());
        for (Matching q : questions) {
            int n = q.size();
            double yBar = 0, gBar = 0, sBar = 0;
            for (int i = 0; i < n; i++) {
                yBar += q.y[i];
                gBar += q.model.contentScore[i];
                sBar += severity[q.grader[i]];
            }
            yBar /= n;
            gBar /= n;
            sBar /= n;
            for (int i = 0; i < n; i++) {
                int g = q.grader[i];
                double w = q.maxMarks;
                double dy = q.y[i] - yBar, dg = q.model.contentScore[i] - gBar, ds = severity[g] - sBar;
                raw[g] += w * dy;
                content[g] += w * dg;
                wSum[g] += w;
                answers[g]++;
                total += w * dy * dy;
                contentVar += w * dg * dg;
                assistantVar += w * ds * ds;
                dev.get(g).add(dg);
            }
        }
        List<CausalFindings.Decomposition> decomposition = new ArrayList<>();
        for (int g = 0; g < graders; g++) {
            if (wSum[g] == 0) continue;
            double r = raw[g] / wSum[g], c = content[g] / wSum[g], m = -severity[g];
            decomposition.add(new CausalFindings.Decomposition(labels.get(g), answers[g], r, c, m, r - c - m));
        }
        double residualVar = Math.max(0, total - contentVar - assistantVar);
        double sumVar = contentVar + assistantVar + residualVar;
        CausalFindings.VarianceShare variance = sumVar <= 0 ? new CausalFindings.VarianceShare(0, 0, 100)
                : new CausalFindings.VarianceShare(100 * contentVar / sumVar, 100 * assistantVar / sumVar,
                        100 * residualVar / sumVar);

        // 2. Overlap and balance.
        double pooledSd = sd(dev.stream().flatMap(List::stream).mapToDouble(Double::doubleValue).toArray());
        double wordSd = sd(questions.stream().flatMapToInt(q -> Arrays.stream(q.words)).asDoubleStream().toArray());
        List<CausalFindings.Overlap> overlap = new ArrayList<>();
        for (int g = 0; g < graders; g++) {
            if (answers[g] == 0) continue;
            int withMatch = 0, total2 = 0;
            for (Matching q : questions) {
                boolean[] has = new boolean[q.size()];
                for (Matching.Pair p : pairs) if (p.q() == q && p.ga() == g) has[p.i()] = true;
                for (int i = 0; i < q.size(); i++) {
                    if (q.grader[i] != g) continue;
                    total2++;
                    if (has[i]) withMatch++;
                }
            }
            double mine = mean(dev.get(g));
            List<Double> others = new ArrayList<>();
            for (int h = 0; h < graders; h++) if (h != g) others.addAll(dev.get(h));
            double before = pooledSd > 0 && !others.isEmpty() ? (mine - mean(others)) / pooledSd : 0;
            double afterSum = 0, lenSum = 0;
            int afterCount = 0;
            for (Matching.Pair p : pairs) {
                if (p.ga() != g) continue;
                afterSum += p.q().model.contentScore[p.i()] - p.q().model.contentScore[p.j()];
                lenSum += p.q().words[p.i()] - p.q().words[p.j()];
                afterCount++;
            }
            double after = afterCount == 0 || pooledSd == 0 ? Double.NaN : afterSum / afterCount / pooledSd;
            double len = afterCount == 0 || wordSd == 0 ? Double.NaN : lenSum / afterCount / wordSd;
            overlap.add(new CausalFindings.Overlap(labels.get(g), total2 == 0 ? 0 : withMatch / (double) total2,
                    before, after, len));
        }

        // 3. The same script marked by two assistants.
        Optional<CausalFindings.DoubleMarked> doubleMarked = doubleMarked(questions, severity, graders, s);

        // 4. Placebo: shuffle the assistant labels among answers alike in content, and rerun.
        CausalFindings.Placebo placebo = placebo(questions, groups, graders, observed, s);

        // 5. Sensitivity to hidden bias, one pair per answer.
        List<CausalFindings.Sensitivity> sensitivity = new ArrayList<>();
        for (int g = 0; g < graders; g++) {
            int positive = 0, nonzero = 0;
            Set<String> seen = new HashSet<>();
            for (Matching.Pair p : pairs) {
                if (p.ga() != g && p.gb() != g) continue;
                int me = p.ga() == g ? p.i() : p.j();
                if (!seen.add(p.q().question + ":" + me)) continue;
                // Equal marks are ties, dropped as in any sign test; otherwise the pair says g marked the
                // same content lower (harsher) or higher than the other TA did.
                if (Math.abs(p.raw()) < 1e-9) continue;
                double harsh = p.ga() == g ? -p.difference() : p.difference();
                if (Math.abs(harsh) < 1e-9) continue;
                nonzero++;
                if (harsh > 0) positive++;
            }
            if (nonzero < 5) continue;
            int direction = positive * 2 >= nonzero ? 1 : -1;
            int k = direction > 0 ? positive : nonzero - positive;
            double pAtOne = Distributions.binomialUpperTail(k, nonzero, 0.5);
            sensitivity.add(new CausalFindings.Sensitivity(labels.get(g), nonzero, direction, pAtOne,
                    rosenbaumGamma(k, nonzero)));
        }

        // 6. Mechanisms.
        List<CausalFindings.Mechanism> mechanisms = mechanisms(questions, pairs, severity, se, draws, outOfLine,
                in, s);
        return new CausalFindings(decomposition, overlap, doubleMarked, placebo, sensitivity, mechanisms, variance);
    }

    /** The largest Gamma at which the sign test still rejects at 5% (1 if it does not even at Gamma = 1). */
    static double rosenbaumGamma(int k, int n) {
        if (Distributions.binomialUpperTail(k, n, 0.5) >= 0.05) return 1.0;
        double lo = 1, hi = 50;
        if (Distributions.binomialUpperTail(k, n, hi / (1 + hi)) < 0.05) return hi;
        for (int it = 0; it < 60; it++) {
            double mid = 0.5 * (lo + hi);
            if (Distributions.binomialUpperTail(k, n, mid / (1 + mid)) < 0.05) lo = mid; else hi = mid;
        }
        return lo;
    }

    private static Optional<CausalFindings.DoubleMarked> doubleMarked(List<Matching> questions, double[] severity,
                                                                      int graders, ContentSettings s) {
        List<Matching.Pair> exact = new ArrayList<>();
        for (Matching q : questions) {
            Map<Integer, List<Integer>> byStudent = new LinkedHashMap<>();
            for (int i = 0; i < q.size(); i++) byStudent.computeIfAbsent(q.student[i], k -> new ArrayList<>()).add(i);
            for (List<Integer> same : byStudent.values()) {
                for (int a = 0; a < same.size(); a++) {
                    for (int b = a + 1; b < same.size(); b++) {
                        int i = same.get(a), j = same.get(b);
                        if (q.grader[i] == q.grader[j]) continue;
                        double d = q.y[i] - q.y[j];
                        exact.add(new Matching.Pair(q, i, j, 1.0, d, d, q.maxMarks, q.grader[i], q.grader[j],
                                q.student[i], q.student[j]));
                    }
                }
            }
        }
        if (exact.size() < 5) return Optional.empty();
        List<List<Integer>> groups = SeverityGraph.groups(exact, graders, 3);
        double[] sev = SeverityGraph.solve(exact, groups, graders, s.severityShrinkage(), null);
        int[] count = new int[graders];
        Set<String> scripts = new HashSet<>();
        for (Matching.Pair p : exact) {
            count[p.ga()]++;
            count[p.gb()]++;
            scripts.add(p.q().question + ":" + p.sa());
        }
        double[] out = new double[graders];
        List<double[]> both = new ArrayList<>();
        for (int g = 0; g < graders; g++) {
            final int gg = g;
            boolean linked = groups.stream().anyMatch(gr -> gr.size() > 1 && gr.contains(gg));
            out[g] = linked ? sev[g] : Double.NaN;
            if (linked) both.add(new double[] {sev[g], severity[g]});
        }
        return Optional.of(new CausalFindings.DoubleMarked(scripts.size(), out, count, correlation(both)));
    }

    private static CausalFindings.Placebo placebo(List<Matching> questions, List<List<Integer>> groups, int graders,
                                                  double observed, ContentSettings s) {
        int[] atLeast = new int[PLACEBO_SHUFFLES];
        double[] spreads = new double[PLACEBO_SHUFFLES];
        IntStream.range(0, PLACEBO_SHUFFLES).parallel().forEach(k -> {
            SplittableRandom rng = new SplittableRandom(~s.seed() * 7_919L + k);
            List<Matching.Pair> fake = new ArrayList<>();
            for (Matching q : questions) fake.addAll(q.pairs(shuffledWithinStrata(q, rng), s.neighbours()));
            double spread = SeverityGraph.spread(SeverityGraph.solve(fake, groups, graders, s.severityShrinkage(), null),
                    groups);
            spreads[k] = spread;
            atLeast[k] = spread >= observed - s.margin() ? 1 : 0;
        });
        int count = Arrays.stream(atLeast).sum();
        return new CausalFindings.Placebo((1.0 + count) / (1.0 + PLACEBO_SHUFFLES), PLACEBO_SHUFFLES,
                Arrays.stream(spreads).average().orElse(0), observed);
    }

    /** The question's assistant labels permuted among answers in the same fifth of predicted quality. */
    private static int[] shuffledWithinStrata(Matching q, SplittableRandom rng) {
        int n = q.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> q.model.contentScore[i]));
        int[] labels = q.grader.clone();
        int strata = Math.max(1, Math.min(5, n / 10));
        for (int st = 0; st < strata; st++) {
            int from = st * n / strata, to = (st + 1) * n / strata;
            for (int i = to - 1; i > from; i--) {
                int j = from + rng.nextInt(i - from + 1);
                int a = order[i], b = order[j];
                int t = labels[a];
                labels[a] = labels[b];
                labels[b] = t;
            }
        }
        return labels;
    }

    private static List<CausalFindings.Mechanism> mechanisms(List<Matching> questions, List<Matching.Pair> pairs,
                                                             double[] severity, double[] se, double[][] draws,
                                                             List<ContentResult.OutOfLine> outOfLine, Input in,
                                                             ContentSettings s) {
        int graders = in.design().graderCount();
        List<String> labels = in.design().graderLabels();
        List<CausalFindings.Mechanism> out = new ArrayList<>();
        for (int g = 0; g < graders; g++) {
            List<CausalFindings.QuestionEffect> effects = new ArrayList<>();
            for (Matching q : questions) {
                List<Double> e = new ArrayList<>();
                for (Matching.Pair p : pairs) {
                    if (p.q() != q) continue;
                    if (p.ga() == g) e.add(-p.difference() + severity[p.gb()]);
                    else if (p.gb() == g) e.add(p.difference() + severity[p.ga()]);
                }
                if (e.size() < 3) continue;
                double m = mean(e);
                double sd = sd(e.stream().mapToDouble(Double::doubleValue).toArray());
                effects.add(new CausalFindings.QuestionEffect(q.label, m, Math.max(sd, 1.0) / Math.sqrt(e.size()),
                        e.size()));
            }
            if (effects.isEmpty() && severity[g] == 0) continue;
            double heterogeneityP = cochranP(effects);
            String name = labels.get(g);
            int slips = (int) outOfLine.stream().filter(o -> o.assistant().equals(name)).count();
            double[] ci = interval(draws, g, severity[g]);
            boolean clear = ci[0] > 0 || ci[1] < 0;
            Drift drift = drift(questions, in, g);
            CausalFindings.Kind kind;
            String why;
            String direction = severity[g] > 0 ? "harsher" : "more lenient";
            if (clear && !Double.isNaN(heterogeneityP) && heterogeneityP < 0.05 && effects.size() >= 2) {
                kind = CausalFindings.Kind.QUESTION_SPECIFIC;
                List<CausalFindings.QuestionEffect> sorted = new ArrayList<>(effects);
                sorted.sort(Comparator.comparingDouble(e -> -Math.abs(e.severity())));
                why = String.format(Locale.ROOT, "%s marked %s than the others, but not evenly: mostly on %s "
                        + "(%+.1f points there), which points to a different reading of that question's rubric "
                        + "rather than general strictness (heterogeneity p = %.3f).", labels.get(g), direction,
                        sorted.get(0).question(), sorted.get(0).severity(), heterogeneityP);
            } else if (clear) {
                kind = CausalFindings.Kind.CONSISTENT_SEVERITY;
                why = String.format(Locale.ROOT, "%s marked %s than the others on answers saying the same thing, by "
                        + "a similar amount on every question (%+.1f points, 95%% interval %+.1f to %+.1f).",
                        labels.get(g), direction, severity[g], ci[0], ci[1]);
            } else if (drift.significant()) {
                kind = CausalFindings.Kind.DRIFT;
                why = String.format(Locale.ROOT, "%s's marks moved by %+.1f points from the first script of the pile "
                        + "to the last, for answers of the same quality (p = %.3f).", labels.get(g), drift.change(),
                        drift.p());
            } else if (slips > 0) {
                kind = CausalFindings.Kind.ISOLATED_SLIPS;
                why = labels.get(g) + " marked like the others overall, but " + slips + " mark"
                        + (slips == 1 ? " is" : "s are") + " far out of line with similar answers: worth checking "
                        + "individually rather than correcting as a whole.";
            } else {
                kind = CausalFindings.Kind.NO_CLEAR_DIFFERENCE;
                why = String.format(Locale.ROOT, "%s's marking is within what chance produces (95%% interval "
                        + "%+.1f to %+.1f points).", labels.get(g), ci[0], ci[1]);
            }
            if (drift.significant() && kind != CausalFindings.Kind.DRIFT) {
                why += String.format(Locale.ROOT, " Their marks also drifted by %+.1f points through the pile.",
                        drift.change());
            }
            out.add(new CausalFindings.Mechanism(labels.get(g), kind, effects, heterogeneityP, slips, why));
        }
        return out;
    }

    private record Drift(boolean significant, double change, double p) {}

    /** Slope of the content-adjusted residual against the marking position, within one assistant's pile. */
    private static Drift drift(List<Matching> questions, Input in, int g) {
        List<double[]> pts = new ArrayList<>();
        for (Matching q : questions) {
            for (int i = 0; i < q.size(); i++) {
                if (q.grader[i] != g) continue;
                DesignCell c = in.responses().get(q.response[i]).cell();
                if (!c.hasPosition()) continue;
                pts.add(new double[] {c.position(), q.y[i] - q.model.predicted[i]});
            }
        }
        if (pts.size() < 20) return new Drift(false, 0, Double.NaN);
        double mx = 0, my = 0;
        for (double[] p : pts) {
            mx += p[0];
            my += p[1];
        }
        mx /= pts.size();
        my /= pts.size();
        double sxx = 0, sxy = 0, lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] p : pts) {
            sxx += (p[0] - mx) * (p[0] - mx);
            sxy += (p[0] - mx) * (p[1] - my);
            lo = Math.min(lo, p[0]);
            hi = Math.max(hi, p[0]);
        }
        if (sxx == 0) return new Drift(false, 0, Double.NaN);
        double slope = sxy / sxx, intercept = my - slope * mx, rss = 0;
        for (double[] p : pts) rss += Math.pow(p[1] - intercept - slope * p[0], 2);
        double seSlope = Math.sqrt(rss / (pts.size() - 2) / sxx);
        double z = seSlope == 0 ? 0 : slope / seSlope;
        double p = 2 * (1 - Distributions.normalCdf(Math.abs(z)));
        double change = slope * (hi - lo);
        return new Drift(p < 0.01 && Math.abs(change) >= 5, change, p);
    }

    static double cochranP(List<CausalFindings.QuestionEffect> effects) {
        List<CausalFindings.QuestionEffect> usable = effects.stream().filter(e -> e.pairs() >= 5).toList();
        if (usable.size() < 2) return Double.NaN;
        double wSum = 0, wy = 0;
        for (CausalFindings.QuestionEffect e : usable) {
            double w = 1 / (e.standardError() * e.standardError());
            wSum += w;
            wy += w * e.severity();
        }
        double pooled = wy / wSum, q = 0;
        for (CausalFindings.QuestionEffect e : usable) {
            q += Math.pow(e.severity() - pooled, 2) / (e.standardError() * e.standardError());
        }
        return Distributions.chiSquareSurvival(q, usable.size() - 1);
    }

    // ---------------------------------------------------------------- small helpers

    private static ContentResult empty(ContentResult.Status status, Input in, double coverage, List<String> labels,
                                       List<String> notes) {
        int g = labels.size();
        return new ContentResult(status, in.embedder(), in.semantic(), coverage, List.of(), labels, new double[g],
                new double[g], new double[g], new int[g], List.of(), new double[0][], Double.NaN, 0, 0, 0, List.of(), List.of(),
                List.of(), Optional.empty(), notes);
    }

    private static double[] interval(double[][] draws, int j, double estimate) {
        if (draws.length == 0) return new double[] {estimate, estimate};
        double[] c = new double[draws.length];
        for (int b = 0; b < c.length; b++) c[b] = draws[b][j];
        Arrays.sort(c);
        return new double[] {c[(int) Math.floor(0.025 * (c.length - 1))], c[(int) Math.ceil(0.975 * (c.length - 1))]};
    }

    static double mad(double[] v) {
        if (v.length == 0) return 0;
        double[] s = v.clone();
        Arrays.sort(s);
        double med = median(s);
        double[] dev = new double[v.length];
        for (int i = 0; i < v.length; i++) dev[i] = Math.abs(v[i] - med);
        Arrays.sort(dev);
        return median(dev);
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    private static double mean(List<Double> v) {
        double s = 0;
        for (double x : v) s += x;
        return v.isEmpty() ? 0 : s / v.size();
    }

    private static double sd(double[] v) {
        if (v.length < 2) return 0;
        double m = 0;
        for (double x : v) m += x;
        m /= v.length;
        double ss = 0;
        for (double x : v) ss += (x - m) * (x - m);
        return Math.sqrt(ss / (v.length - 1));
    }

    private static double correlation(List<double[]> xy) {
        if (xy.size() < 3) return Double.NaN;
        double mx = 0, my = 0;
        for (double[] p : xy) {
            mx += p[0];
            my += p[1];
        }
        mx /= xy.size();
        my /= xy.size();
        double sxx = 0, syy = 0, sxy = 0;
        for (double[] p : xy) {
            sxx += (p[0] - mx) * (p[0] - mx);
            syy += (p[1] - my) * (p[1] - my);
            sxy += (p[0] - mx) * (p[1] - my);
        }
        return sxx == 0 || syy == 0 ? Double.NaN : sxy / Math.sqrt(sxx * syy);
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.0f%%", 100 * share);
    }
}
