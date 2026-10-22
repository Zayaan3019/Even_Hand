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
import in.ac.iitm.evenhand.numerics.Distributions;

/**
 * Hands the content-anchored severities to the outcome stage in the same form as the
 * marks-only fit, so grades are corrected, re-check lists drawn and reports written by exactly
 * the same tested code whichever estimate the course could support.
 *
 * <p>The answers can stand in for the assumption the marks-only estimate needs only when they
 * are trustworthy and link every assistant: {@link #licence} says when.
 */
public final class ContentFit {

    /**
     * The Hausman test's level. 10% rather than 5%, because the two errors are not equally costly: wrongly
     * trusting the marks when piles differed misplaces a TA by several points (five times the answers'
     * error on real data), while wrongly preferring the answers costs a little precision.
     */
    static final double HAUSMAN_ALPHA = 0.10;

    private ContentFit() {
    }

    /**
     * Which estimate corrects the grades, and why.
     *
     * @param useAnswers  the answer-anchored estimate, rather than the marks-only one
     * @param chiSquare   the Hausman statistic comparing the two (NaN when there was nothing to compare)
     * @param pValue      its p-value: small means the two disagree, so the piles were not alike
     * @param explanation the choice in the instructor's words
     */
    public record Choice(boolean useAnswers, double chiSquare, int df, double pValue, String explanation) {}

    /**
     * Chooses between the marks-only and the answer-anchored estimate by a Hausman (1978) test.
     *
     * <p>When the marking alone needs the assumption that every TA's pile was alike, the two estimates
     * differ in what they rest on: the marks-only one is the more precise if the assumption holds, and
     * wrong if it does not; the answer-anchored one holds either way. If they agree within their
     * uncertainty, the answers have confirmed the assumption and the more precise estimate is used.
     * If they disagree, the piles were not alike, and the answers' estimate is used. On real
     * double-graded answers this choice is what keeps the error low both when piles are dealt at random
     * (where the marks estimate wins) and when they are not (where it is five times worse).
     */
    public static Choice choose(AuditVerdict fromMarking, Optional<FittedModel> marks, ContentResult content) {
        if (fromMarking instanceof AuditVerdict.Estimable) {
            return new Choice(false, Double.NaN, 0, Double.NaN, "The marking alone places every TA on one scale with no "
                    + "assumption, so the marks estimate is used; the answers give an independent check.");
        }
        if (!content.usable() || !content.linksEveryAssistant() || fromMarking.exposure().isNone()) {
            return new Choice(false, Double.NaN, 0, Double.NaN, marks.isPresent()
                    ? "The answers could not compare every TA, so the marks estimate is used."
                    : "Neither the marking nor the answers can compare the TAs.");
        }
        if (marks.isEmpty()) {
            return new Choice(true, Double.NaN, 0, Double.NaN, "The marking alone cannot compare the TAs; the answers can, "
                    + "so their estimate is used.");
        }
        FittedModel m = marks.get();
        double chi = 0;
        int terms = 0;
        double largest = 0;
        for (int j = 0; j < content.graderLabels().size(); j++) {
            // Sampling errors only: the bias allowance widens intervals for coverage, and would only blunt the test.
            double se2 = Math.pow(m.graders().standardErrors()[j], 2) + Math.pow(content.samplingError()[j], 2);
            if (!(se2 > 0)) continue;
            double d = m.severity(j) - content.severity()[j];
            chi += d * d / se2;
            terms++;
            largest = Math.max(largest, Math.abs(d));
        }
        int df = Math.max(1, terms - 1);
        double p = terms < 2 ? Double.NaN : Distributions.chiSquareSurvival(chi, df);
        if (Double.isFinite(p) && p < HAUSMAN_ALPHA) {
            return new Choice(true, chi, df, p, String.format(Locale.ROOT, "The marks-only estimate assumes every TA's "
                    + "pile was alike, and the answers show it was not (Hausman test p = %.3f): the marks alone would have "
                    + "misplaced a TA by up to %.1f points. The answers' estimate is used.", p, largest));
        }
        return new Choice(false, chi, df, p, String.format(Locale.ROOT, "The answers confirm that the TAs' piles were "
                + "comparable (the two estimates agree, Hausman test p = %.2f), so the more precise estimate from the "
                + "marks is used.", p));
    }

    /**
     * The upgraded verdict, when the answers can compare every assistant on one scale without
     * the assumption the marking alone needed; empty otherwise.
     *
     * <p>The marking's own verdict is kept when it was already assumption-free
     * ({@link AuditVerdict.Estimable}): the answers then serve as an independent check.
     */
    public static Optional<AuditVerdict.EstimableFromContent> licence(AuditVerdict fromMarking, Optional<FittedModel> marks,
                                                                      ContentResult content) {
        if (fromMarking instanceof AuditVerdict.EstimableFromContent) return Optional.empty();
        if (!choose(fromMarking, marks, content).useAnswers()) return Optional.empty();
        int pairs = 0;
        for (ContentResult.QuestionSummary q : content.questions()) pairs += q.pairs();
        String evidence = String.format(Locale.ROOT, "%d matched pairs of answers link all %d TAs; what the "
                + "answers say explains %.0f%% of how marks vary within a pile, and %.0f%% of answers have a close "
                + "match marked by someone else.", pairs, content.graderLabels().size(), 100 * content.signal(),
                100 * content.matchedShare());
        return Optional.of(AuditVerdict.EstimableFromContent.upgrade(fromMarking, evidence));
    }

    /**
     * The marks estimate with the same bias allowance as the answers' one: used when it was kept because
     * the answers confirmed the equal-piles assumption. The assumption is then supported, not proved, and
     * on real double-graded answers the additive marks model misses by about the allowance when graders'
     * harshness varies with answer quality.
     */
    public static FittedModel withAllowance(FittedModel m, double allowance) {
        double[][] draws = new double[m.severityDraws().length][];
        for (int b = 0; b < draws.length; b++) draws[b] = m.severityDraws()[b].clone();
        double[] est = m.graders().estimates();
        ContentAnalysis.biasAware(draws, est, allowance);
        double[] se = new double[est.length];
        if (draws.length > 1) {
            for (int j = 0; j < est.length; j++) {
                double mean = 0, ss = 0;
                for (double[] d : draws) mean += d[j];
                mean /= draws.length;
                for (double[] d : draws) ss += (d[j] - mean) * (d[j] - mean);
                se[j] = Math.sqrt(ss / (draws.length - 1));
            }
        }
        FacetEstimates g = m.graders();
        FacetEstimates widened = new FacetEstimates(g.facet(), g.labels(), est.clone(), se, g.counts(), g.extreme());
        return new FittedModel(m.verdict(), m.anchoring(), m.mean(), m.students(), m.questions(), widened, m.groups(),
                m.residualSd(), draws, m.noEffect(), m.diagnostics());
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
                "Severities average zero within each group of TAs the answers link, so each figure is read "
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
