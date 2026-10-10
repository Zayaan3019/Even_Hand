package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.DesignProfiler;
import in.ac.iitm.evenhand.design.IdentifiabilityAudit;
import in.ac.iitm.evenhand.estimate.SeverityEstimator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.ingest.CsvIngest;
import in.ac.iitm.evenhand.ingest.Pseudonymiser;
import in.ac.iitm.evenhand.outcome.Composite;
import in.ac.iitm.evenhand.outcome.Outcome;
import in.ac.iitm.evenhand.report.ReportInput;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The whole system, in order. Each line is one stage, and each stage is one module:
 *
 * <pre>
 *   1 ingest    marks.csv  -> pseudonymised responses + marking design   (eh-ingest)
 *   2 audit     design     -> can severity be measured? could it harm?  (eh-design)
 *   3 estimate  responses  -> severity per assistant, with intervals    (eh-estimate)
 *   4 outcome   severity   -> grades as marked vs corrected, re-check list (eh-outcome)
 *   5 report    all above  -> instructor report + committee sheet      (eh-report)
 * </pre>
 *
 * Stages 3 and 4 run only when stage 2 permits it.
 */
public final class Pipeline {

    /** Everything a run produced, for the CLI to print and the benchmark to score. */
    public record Run(CsvIngest.IngestResult ingest, AuditVerdict verdict, Optional<FittedModel> model,
                      Optional<Outcome.Result> outcome, double[] assessmentWeights, ReportInput report) {}

    private Pipeline() {
    }

    public static Run run(BufferedReader csv, Pseudonymiser pseudonymiser, CourseConfig config, Settings settings)
            throws IOException {
        // 1. Ingest: identifiers are replaced before any response exists.
        CsvIngest.IngestResult ingest = CsvIngest.read(csv, pseudonymiser);
        if (ingest.responses().isEmpty()) {
            throw new IllegalArgumentException("no usable marks in the file:\n" + render(ingest.diagnostics()));
        }
        MarkingDesign design = ingest.design();
        double[] weights = weightsFor(ingest.assessmentLabels(), config);
        Composite composite = new Composite(ingest.responses(), design, weights);

        // 2. Audit: reads who marked what, never the marks.
        AuditVerdict verdict = IdentifiabilityAudit.audit(design, composite.questionWeights());

        // 3 and 4. Estimate and grade, only if the audit allows it.
        Optional<FittedModel> model = Optional.empty();
        Optional<Outcome.Result> outcome = Optional.empty();
        if (verdict.permitsEstimation()) {
            AnchoredDesign anchored = AnchoredDesign.of(design, verdict, Anchoring.standard());
            FittedModel fit = SeverityEstimator.fit(anchored, ingest.responses(), settings);
            model = Optional.of(fit);
            outcome = Optional.of(Outcome.assess(fit, ingest.responses(), design, weights,
                    config.policy(), Outcome.Gate.standard()));
        }

        // 5. Report inputs: what was measured, declared or defaulted.
        List<Diagnostic> diagnostics = new ArrayList<>(ingest.diagnostics());
        diagnostics.addAll(verdict.diagnostics());
        List<String> footnotes = new ArrayList<>();
        footnotes.add(DesignProfiler.profile(design, ingest.responses()).arrangement().describe("marking arrangement"));
        footnotes.add("assessment weights: " + describeWeights(ingest.assessmentLabels(), weights)
                + (config.weights().isEmpty() ? " (DEFAULTED: equal; pass --weights)" : " (declared)"));
        footnotes.add("cut-off rule: " + config.policy().kind() + ", S by " + config.policy().sBandPolicy()
                + (config.ruleDeclared() ? " (declared)" : " (DEFAULTED: departmental convention; pass --rule)"));
        footnotes.add(String.format(java.util.Locale.ROOT,
                "estimator: shrinkage %.0f/%.0f marks, %d bootstrap draws, %d no-effect simulations, seed %d",
                settings.studentShrinkage(), settings.graderShrinkage(), settings.bootstrapDraws(),
                settings.nullSimulations(), settings.seed()));
        footnotes.add("key fingerprint: " + ingest.saltFingerprint());
        ReportInput report = new ReportInput(config.name(), design, verdict, model, outcome, diagnostics, footnotes);
        return new Run(ingest, verdict, model, outcome, weights, report);
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
