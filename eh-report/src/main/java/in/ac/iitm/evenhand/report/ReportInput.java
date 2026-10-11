package in.ac.iitm.evenhand.report;

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
 * @param verdict      the audit's verdict
 * @param model        the fit, absent when the audit refused estimation
 * @param outcome      grades as marked and corrected, absent with the model
 * @param diagnostics  everything ingest and the audit had to say
 * @param footnotes    which inputs were measured, declared or defaulted
 */
public record ReportInput(String courseLabel,
                          MarkingDesign design,
                          AuditVerdict verdict,
                          Optional<FittedModel> model,
                          Optional<Outcome.Result> outcome,
                          List<Diagnostic> diagnostics,
                          List<String> footnotes) {

    public ReportInput {
        diagnostics = List.copyOf(diagnostics);
        footnotes = List.copyOf(footnotes);
    }
}
