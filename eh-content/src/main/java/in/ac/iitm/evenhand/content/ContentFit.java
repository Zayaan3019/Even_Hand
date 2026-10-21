package in.ac.iitm.evenhand.content;

import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Facet;
import in.ac.iitm.evenhand.core.FacetEstimates;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Hands the content-anchored severities to the outcome stage in the same form as the
 * marks-only fit, so grades are corrected, re-check lists drawn and reports written by exactly
 * the same tested code whichever estimate the course could support.
 *
 * <p>The answers can stand in for the assumption the marks-only estimate needs only when they
 * are trustworthy and link every assistant: {@link #licence} says when.
 */
public final class ContentFit {

    private ContentFit() {
    }

    /**
     * The upgraded verdict, when the answers can compare every assistant on one scale without
     * the assumption the marking alone needed; empty otherwise.
     *
     * <p>The marking's own verdict is kept when it was already assumption-free
     * ({@link AuditVerdict.Estimable}): the answers then serve as an independent check.
     */
    public static Optional<AuditVerdict.EstimableFromContent> licence(AuditVerdict fromMarking, ContentResult content) {
        if (fromMarking instanceof AuditVerdict.Estimable || fromMarking instanceof AuditVerdict.EstimableFromContent) {
            return Optional.empty();
        }
        if (!content.usable() || !content.linksEveryAssistant()) return Optional.empty();
        if (fromMarking.exposure().isNone()) return Optional.empty();
        int pairs = 0;
        for (ContentResult.QuestionSummary q : content.questions()) pairs += q.pairs();
        String evidence = String.format(Locale.ROOT, "%d matched pairs of answers link all %d assistants; what the "
                + "answers say explains %.0f%% of how marks vary within a pile, and %.0f%% of answers have a close "
                + "match marked by someone else.", pairs, content.graderLabels().size(), 100 * content.signal(),
                100 * content.matchedShare());
        return Optional.of(AuditVerdict.EstimableFromContent.upgrade(fromMarking, evidence));
    }

    /** The content estimate as a {@link FittedModel} under the given (upgraded) verdict. */
    public static FittedModel toFittedModel(ContentResult content, AuditVerdict verdict, MarkingDesign design,
                                            List<Response> responses) {
        int graders = design.graderCount();
        int[] marks = new int[graders];
        double mean = 0, w = 0;
        for (Response r : responses) {
            marks[r.grader()]++;
            mean += r.maxMarks() * 100.0 * r.score() / r.maxScore();
            w += r.maxMarks();
        }
        mean = w == 0 ? 0 : mean / w;
        FacetEstimates graderFacet = new FacetEstimates(Facet.GRADER, design.graderLabels(),
                content.severity().clone(), content.standardError().clone(), marks, new boolean[graders]);
        FacetEstimates students = zeros(Facet.STUDENT, design.studentLabels());
        FacetEstimates questions = zeros(Facet.QUESTION, design.questionLabels());
        List<List<Integer>> groups = new ArrayList<>(content.groups());
        double residual = 0;
        return new FittedModel(verdict, new Anchoring(Anchoring.Kind.GRADERS_CENTRED,
                "Severities average zero within each group of assistants the answers link, so each figure is read "
                + "against the panel average for answers saying the same thing."),
                mean, students, questions, graderFacet, groups, residual, content.draws(),
                new FittedModel.NoEffectTest(content.observedSpread(), content.pValue(), content.draws().length),
                new FittedModel.FitDiagnostics(1, 0, true));
    }

    private static FacetEstimates zeros(Facet facet, List<String> labels) {
        int n = labels.size();
        return new FacetEstimates(facet, labels, new double[n], new double[n], new int[n], new boolean[n]);
    }
}
