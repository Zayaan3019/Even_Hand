package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.content.ContentAnalysis;
import in.ac.iitm.evenhand.content.ContentFit;
import in.ac.iitm.evenhand.content.ContentResult;
import in.ac.iitm.evenhand.content.ContentSettings;
import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.DesignProfiler;
import in.ac.iitm.evenhand.design.IdentifiabilityAudit;
import in.ac.iitm.evenhand.embed.Embedders;
import in.ac.iitm.evenhand.embed.SentenceEmbedder;
import in.ac.iitm.evenhand.estimate.SeverityEstimator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.ingest.CsvIngest;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.outcome.Composite;
import in.ac.iitm.evenhand.outcome.Outcome;
import in.ac.iitm.evenhand.report.ReportInput;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The whole system, in order. Each line is one stage, and each stage is one module:
 *
 * <pre>
 *   1 ingest    marks.csv  -> pseudonymised responses, answers, marking design (eh-ingest)
 *   2 audit     design     -> can severity be measured? could it harm?        (eh-design)
 *   3 estimate  responses  -> severity per assistant from the marks           (eh-estimate)
 *   3b content  answers    -> answers as vectors; severity from matched answers,
 *                             and why marks differed                         (eh-embed, eh-content)
 *   4 outcome   severity   -> grades as marked vs corrected, re-check list    (eh-outcome)
 *   5 report    all above  -> instructor report + committee sheet            (eh-report)
 * </pre>
 *
 * <p>Which severity corrects the grades: the marks-only estimate when the marking alone links
 * every assistant with no assumption; otherwise the answer-anchored estimate, when the answers
 * are informative enough and link every assistant; otherwise the marks-only estimate under the
 * assumption the audit names. Both are always reported when both exist.
 */
public final class Pipeline {

    /** Where a run has got to, for the page's progress bar. Fractions run 0..1 over the whole run. */
    @FunctionalInterface
    public interface Progress {
        void at(String stage, double fraction);

        static Progress none() {
            return (stage, fraction) -> { };
        }
    }

    /** Everything a run produced, for the CLI to print, the page to show and the tests to check. */
    public record Run(CsvIngest.IngestResult ingest, AuditVerdict markingVerdict, AuditVerdict verdict,
                      Optional<FittedModel> model, Optional<FittedModel> marksModel, Optional<ContentResult> content,
                      Optional<Outcome.Result> outcome, double[] assessmentWeights, ReportInput report,
                      Map<String, String> manifest) {}

    /** The estimators' settings together. */
    public record Options(Settings marks, ContentSettings content, Supplier<Embedders.Choice> embedder) {
        public static Options defaults() {
            return new Options(Settings.defaults(), ContentSettings.defaults(), Embedders::best);
        }
    }

    /** Answers embedded per progress step, so a long file reports as it goes. */
    private static final int EMBED_CHUNK = 256;

    private Pipeline() {
    }

    /** The marks-only pipeline with default content settings: what a file without answers needs. */
    public static Run run(BufferedReader csv, Pseudonymiser pseudonymiser, CourseConfig config, Settings settings)
            throws IOException {
        return run(readAll(csv), pseudonymiser, config,
                new Options(settings, ContentSettings.defaults(), Embedders::best), Progress.none());
    }

    public static Run run(String csvText, Pseudonymiser pseudonymiser, CourseConfig config, Options options,
                          Progress progress) throws IOException {
        // 1. Ingest: identifiers are replaced before any response exists.
        progress.at("Reading the file", 0.02);
        CsvIngest.IngestResult ingest = CsvIngest.read(new BufferedReader(new StringReader(csvText)), pseudonymiser);
        if (ingest.responses().isEmpty()) {
            throw new IllegalArgumentException("no usable marks in the file:\n" + render(ingest.diagnostics()));
        }
        MarkingDesign design = ingest.design();
        double[] weights = weightsFor(ingest.assessmentLabels(), config);
        Composite composite = new Composite(ingest.responses(), design, weights);

        // 2. Audit: reads who marked what, never the marks.
        progress.at("Checking who marked what", 0.06);
        AuditVerdict markingVerdict = IdentifiabilityAudit.audit(design, composite.questionWeights());

        // 3. Estimate from the marks, where the audit allows it.
        Optional<FittedModel> marksModel = Optional.empty();
        if (markingVerdict.permitsEstimation()) {
            progress.at("Estimating each TA's severity from the marks", 0.10);
            AnchoredDesign anchored = AnchoredDesign.of(design, markingVerdict, Anchoring.standard());
            marksModel = Optional.of(SeverityEstimator.fit(anchored, ingest.responses(), options.marks()));
        }

        // 3b. The written answers, if the file has them.
        Optional<ContentResult> content = Optional.empty();
        String embedderNote = "no written answers in the file";
        String embedderId = "none";
        if (ingest.answerCount() > 0) {
            progress.at("Loading the answer-reading model", 0.25);
            Embedders.Choice choice = options.embedder().get();
            embedderNote = choice.note();
            embedderId = choice.embedder().id();
            float[][] vectors = embed(choice.embedder(), ingest.answers(), progress, 0.27, 0.80);
            progress.at("Comparing answers across TAs, and the causal checks", 0.82);
            content = Optional.of(ContentAnalysis.analyse(new ContentAnalysis.Input(ingest.responses(),
                    ingest.answers(), vectors, design, choice.embedder().id(), choice.embedder().semantic()),
                    options.content()));
        }

        // Which estimate corrects the grades.
        AuditVerdict verdict = markingVerdict;
        Optional<FittedModel> model = marksModel;
        Optional<AuditVerdict.EstimableFromContent> licence = content.flatMap(c -> ContentFit.licence(markingVerdict, c));
        if (licence.isPresent()) {
            verdict = licence.get();
            model = Optional.of(ContentFit.toFittedModel(content.get(), verdict, design, ingest.responses()));
        }

        // 4. Grades as marked and corrected.
        progress.at("Regrading the course under its own rule", 0.92);
        Optional<Outcome.Result> outcome = model.map(m -> Outcome.assess(m, ingest.responses(), design, weights,
                config.policy(), Outcome.Gate.standard()));

        // 5. Report inputs: what was measured, declared or defaulted, and how to reproduce it.
        progress.at("Writing the report", 0.97);
        List<Diagnostic> diagnostics = new ArrayList<>(ingest.diagnostics());
        diagnostics.addAll(markingVerdict.diagnostics());
        Map<String, String> manifest = manifest(csvText, ingest, options, embedderId, embedderNote, config, weights);
        List<String> footnotes = new ArrayList<>();
        footnotes.add(DesignProfiler.profile(design, ingest.responses()).arrangement().describe("marking arrangement"));
        footnotes.add("assessment weights: " + describeWeights(ingest.assessmentLabels(), weights)
                + (config.weights().isEmpty() ? " (DEFAULTED: equal; pass --weights)" : " (declared)"));
        footnotes.add("cut-off rule: " + config.policy().kind() + ", S by " + config.policy().sBandPolicy()
                + (config.ruleDeclared() ? " (declared)" : " (DEFAULTED: departmental convention; pass --rule)"));
        footnotes.add("severity used to correct grades: " + (licence.isPresent()
                ? "from matched written answers (the marks alone needed an assumption the answers remove)"
                : model.isPresent() ? "from the marks" : "none (not estimable)"));
        footnotes.add(String.format(Locale.ROOT,
                "marks estimator: shrinkage %.0f/%.0f marks, %d bootstrap draws, %d no-effect simulations, seed %d",
                options.marks().studentShrinkage(), options.marks().graderShrinkage(), options.marks().bootstrapDraws(),
                options.marks().nullSimulations(), options.marks().seed()));
        if (content.isPresent()) {
            ContentSettings cs = options.content();
            footnotes.add("answers read by: " + embedderId + "; " + embedderNote);
            footnotes.add(String.format(Locale.ROOT, "answer matching: %d nearest answer(s) by another TA at cosine "
                    + ">= %.2f (lexical %.2f), %d pairs to link two TAs, content signal >= %.0f%%, %d-fold cross-fitting, "
                    + "%d bootstrap draws, seed %d", cs.neighbours(), cs.caliper(), cs.lexicalCaliper(),
                    cs.minLinkPairs(), 100 * cs.minSignal(), cs.folds(), cs.bootstrapDraws(), cs.seed()));
        }
        footnotes.add("run: " + manifest.get("input") + ", " + manifest.get("when") + ", Even Hand "
                + manifest.get("version") + ", Java " + manifest.get("java"));
        footnotes.add("key fingerprint: " + ingest.saltFingerprint());
        ReportInput report = new ReportInput(config.name(), design, verdict, model, outcome, diagnostics, footnotes,
                content, licence.isPresent() ? marksModel : Optional.empty());
        progress.at("Done", 1.0);
        return new Run(ingest, markingVerdict, verdict, model, marksModel, content, outcome, weights, report, manifest);
    }

    /** One unit vector per response (null where it has no answer), embedded a chunk at a time. */
    static float[][] embed(SentenceEmbedder embedder, List<String> answers, Progress progress, double from, double to) {
        List<String> unique = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        for (String a : answers) {
            if (!a.isBlank() && !index.containsKey(a)) {
                index.put(a, unique.size());
                unique.add(a);
            }
        }
        float[][] byUnique = new float[unique.size()][];
        if (!embedder.semantic()) {
            // The lexical embedder weights words by how rare they are across all answers: one call.
            byUnique = embedder.embed(unique);
        } else {
            for (int start = 0; start < unique.size(); start += EMBED_CHUNK) {
                int end = Math.min(unique.size(), start + EMBED_CHUNK);
                progress.at(String.format(Locale.ROOT, "Reading the answers (%d of %d)", start, unique.size()),
                        from + (to - from) * start / Math.max(1, unique.size()));
                float[][] part = embedder.embed(unique.subList(start, end));
                System.arraycopy(part, 0, byUnique, start, part.length);
            }
        }
        float[][] out = new float[answers.size()][];
        for (int i = 0; i < answers.size(); i++) {
            String a = answers.get(i);
            if (!a.isBlank()) out[i] = byUnique[index.get(a)];
        }
        return out;
    }

    /** How to reproduce the run: what went in, with which settings and model. Nothing identifying. */
    private static Map<String, String> manifest(String csvText, CsvIngest.IngestResult ingest, Options options,
                                                String embedderId, String embedderNote, CourseConfig config,
                                                double[] weights) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("version", version());
        m.put("when", Instant.now().toString());
        m.put("java", System.getProperty("java.version"));
        m.put("input", "input SHA-256 " + sha256(csvText).substring(0, 16) + "...");
        m.put("rows", String.valueOf(ingest.responses().size()));
        m.put("answers", String.valueOf(ingest.answerCount()));
        m.put("embedder", embedderId);
        m.put("embedderNote", embedderNote);
        m.put("rule", config.policy().kind().toString());
        m.put("weights", describeWeights(ingest.assessmentLabels(), weights));
        m.put("marksSeed", String.valueOf(options.marks().seed()));
        m.put("contentSeed", String.valueOf(options.content().seed()));
        return m;
    }

    static String version() {
        String v = Pipeline.class.getPackage().getImplementationVersion();
        return v == null ? "development build" : v;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String readAll(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1 << 14];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    /** Weights in assessment-index order; every assessment must be named if any is. */
    static double[] weightsFor(List<String> assessments, CourseConfig config) {
        double[] w = new double[Math.max(1, assessments.size())];
        if (config.weights().isEmpty()) {
            java.util.Arrays.fill(w, 1.0);
            return w;
        }
        for (int a = 0; a < assessments.size(); a++) {
            Double given = config.weights().get(assessments.get(a));
            if (given == null) {
                throw new IllegalArgumentException("no weight given for assessment '" + assessments.get(a)
                        + "'; the file has " + assessments);
            }
            if (!(given > 0) || !Double.isFinite(given)) {
                throw new IllegalArgumentException("the weight for '" + assessments.get(a) + "' must be a positive number");
            }
            w[a] = given;
        }
        for (String named : config.weights().keySet()) {
            if (!assessments.contains(named)) {
                throw new IllegalArgumentException("weight given for '" + named + "', which is not in the file "
                        + assessments);
            }
        }
        return w;
    }

    private static String describeWeights(List<String> assessments, double[] w) {
        StringBuilder sb = new StringBuilder();
        for (int a = 0; a < assessments.size(); a++) {
            if (a > 0) sb.append(", ");
            sb.append(assessments.get(a).isEmpty() ? "(single assessment)" : assessments.get(a)).append('=')
              .append(w[a] == Math.rint(w[a]) ? String.valueOf((long) w[a]) : String.valueOf(w[a]));
        }
        return sb.toString();
    }

    private static String render(List<Diagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : diagnostics) sb.append(d.render()).append('\n');
        return sb.toString();
    }
}
