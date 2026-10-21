package in.ac.iitm.evenhand.report;

import in.ac.iitm.evenhand.content.ContentResult;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.outcome.Outcome;

import java.util.List;
import java.util.Optional;

/**
 * Everything a report narrates. The report adds no numbers of its own: it only says
 * what the earlier stages found, in the instructor's words.
 *
 * @param courseLabel  how the course is named in output; never a roll number
 * @param design       the marking arrangement (no marks)
 * @param verdict      the verdict the grades were corrected under: the marking's own, or the
 *                     upgrade the answers made possible ({@link AuditVerdict.EstimableFromContent})
 * @param model        the fit the grades were corrected with, absent when nothing was estimable
 * @param outcome      grades as marked and corrected, absent with the model
 * @param diagnostics  everything ingest and the audit had to say
 * @param footnotes    which inputs were measured, declared or defaulted, and the run manifest
 * @param content      what the written answers said, when the file carried any
 * @param marksOnly    the estimate from the marks alone, when the answers supplied the main one,
 *                     so the two can be set side by side
 */
public record ReportInput(String courseLabel,
                          MarkingDesign design,
                          AuditVerdict verdict,
                          Optional<FittedModel> model,
                          Optional<Outcome.Result> outcome,
                          List<Diagnostic> diagnostics,
                          List<String> footnotes,
                          Optional<ContentResult> content,
                          Optional<FittedModel> marksOnly) {

    public ReportInput {
        diagnostics = List.copyOf(diagnostics);
        footnotes = List.copyOf(footnotes);
    }

    /** A report on the marks alone, for a file without written answers. */
    public ReportInput(String courseLabel, MarkingDesign design, AuditVerdict verdict, Optional<FittedModel> model,
                       Optional<Outcome.Result> outcome, List<Diagnostic> diagnostics, List<String> footnotes) {
        this(courseLabel, design, verdict, model, outcome, diagnostics, footnotes, Optional.empty(), Optional.empty());
    }

    /** Whether the grades were corrected with severities the answers anchored. */
    public boolean anchoredByAnswers() {
        return verdict instanceof AuditVerdict.EstimableFromContent;
    }
}
