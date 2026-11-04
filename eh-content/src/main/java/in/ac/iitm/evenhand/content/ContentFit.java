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
 * are trustworthy and link every assistant: {@link #licence} says when. Otherwise they test whether the
 * piles were alike, and how much of the gap in marks the piles explain: {@link #choose}.
 */
public final class ContentFit {

    /**
     * The level of the test that the piles differ. 10% rather than 5%, because the two errors are not equally
     * costly: treating unequal piles as equal blames a TA for the pile, while adjusting equal piles costs a
     * little precision.
     */
    static final double PILE_ALPHA = 0.10;

    private ContentFit() {
    }

    /**
     * Which estimate corrects the grades, and why.
     *
     * @param useAnswers     the answer-anchored estimate, because the marks alone cannot compare the TAs
     * @param adjustForPiles the marks estimate less what the piles' strength explains ({@link #adjustedForPiles})
     * @param pileGap        how far the piles' strength spreads, from what their answers say (NaN if not measured)
     * @param pValue         the chance of a spread that large from dealing alone: small means the piles differ
     * @param explanation    the choice in the instructor's words
     */
    public record Choice(boolean useAnswers, boolean adjustForPiles, double pileGap, double pValue, String explanation) {}

    /**
     * Chooses by asking whether the answers show the piles differ, before any mark is consulted.
     *
     * <p>This replaced a Hausman (1978) test between the marks' and the answers' estimates. On held-out real data
     * the answers explained only a fifth of the marks, the two estimates shared most of the same bias, and the test
     * saw no disagreement: it reported TAs differing in 85-99% of deals where they did not. A pretest with little
     * power is known to behave this way (Guggenberger 2010). The piles' content scores carry no marking noise and
     * no TA's severity, so they show a difference between piles far more plainly.
     */
    public static Choice choose(AuditVerdict fromMarking, Optional<FittedModel> marks, ContentResult content) {
        if (fromMarking instanceof AuditVerdict.Estimable) {
            return new Choice(false, false, Double.NaN, Double.NaN, "The marking alone places every TA on one scale with no "
                    + "assumption, so the marks estimate is used; the answers give an independent check.");
        }
        if (marks.isEmpty()) {
            return content.usable() && content.linksEveryAssistant() && !fromMarking.exposure().isNone()
                    ? new Choice(true, false, Double.NaN, Double.NaN, "The marking alone cannot compare the TAs; the "
                            + "answers can, so their estimate is used.")
                    : new Choice(false, false, Double.NaN, Double.NaN, "Neither the marking nor the answers can compare the TAs.");
        }
        PileBalance balance = content.balance();
        if (fromMarking.exposure().isNone() || balance.draws().length == 0) {
            return new Choice(false, false, Double.NaN, Double.NaN,
                    "The answers could not compare the TAs' piles, so the marks estimate is used.");
        }
        double spread = spread(balance.gap());
        int beyond = 0;
        for (double[] d : balance.draws()) {
            double[] centred = new double[d.length];
            for (int j = 0; j < d.length; j++) centred[j] = d[j] - balance.gap()[j];
            if (spread(centred) >= spread) beyond++;
        }
        double p = (1.0 + beyond) / (1.0 + balance.draws().length);
        // A close call is said to be one: on real answers, a small change to a file can tip it either way.
        String close = p >= 0.05 && p < 0.20 ? " This was a close call, so read the interval rather than the figure." : "";
        if (p < PILE_ALPHA) {
            return new Choice(false, true, spread, p, String.format(Locale.ROOT, "The answers show the TAs' piles were "
                    + "not alike (p = %.3f), so part of the gap in marks is the piles, not the TAs. The severities are "
                    + "reduced by that part, taking what the answers cannot show about a script to differ between piles "
                    + "in proportion to what they can. That is an assumption the answers cannot check.", p) + close);
        }
        return new Choice(false, false, spread, p, String.format(Locale.ROOT, "The answers show no clear difference "
                + "in strength between the TAs' piles (p = %.2f), so the marks estimate is used. That supports the "
                + "assumption that the piles were alike; it does not prove it.", p) + close);
    }

    /** Root mean square, the size of a set of gaps that already average zero. */
    private static double spread(double[] gaps) {
        double ss = 0;
        for (double g : gaps) ss += g * g;
        return gaps.length == 0 ? 0 : Math.sqrt(ss / gaps.length);
    }

    /**
     * The marks estimate less what the piles' strength explains, when the answers show the piles differ.
     *
     * <p>A pile that looks {@code gap} points stronger from its answers shows only the part of quality the answers
     * reveal. Taking what they do not reveal to differ between piles in proportion (Altonji, Elder and Taber 2005;
     * Oster 2019, with delta = 1 and a maximum R-squared of 1), the pile is {@code gap / R^2} points stronger, where
     * R^2 is the share of how scripts' marks vary that the answers explain. That is how far the marks flatter the
     * TA who had it. Neither number is tuned, and the assumption is named in the report.
     *
     * @param floor the least R^2 divided by, so that answers saying almost nothing give a wide interval, not a wild one
     */
    public static FittedModel adjustedForPiles(FittedModel m, ContentResult content, double floor, double allowance) {
        PileBalance balance = content.balance();
        double scale = 1 / Math.max(balance.scriptSignal(), floor);
        double[] est = m.graders().estimates().clone();
        for (int j = 0; j < est.length; j++) est[j] += balance.gap()[j] * scale;
        double[][] draws = new double[m.severityDraws().length][];
        for (int b = 0; b < draws.length; b++) {
            draws[b] = m.severityDraws()[b].clone();
            double[] gap = balance.draws()[b % balance.draws().length];
            for (int j = 0; j < est.length; j++) draws[b][j] += gap[j] * scale;
        }
        double observed = SeverityGraph.spread(est, m.groups());
        double pValue = ContentAnalysis.recentredP(draws, est, m.groups(), observed, allowance);
        ContentAnalysis.biasAware(draws, est, allowance);
        FacetEstimates g = m.graders();
        FacetEstimates moved = new FacetEstimates(g.facet(), g.labels(), est, standardErrors(draws, est.length), g.counts(), g.extreme());
        return new FittedModel(m.verdict(), m.anchoring(), m.mean(), m.students(), m.questions(), moved, m.groups(),
                m.residualSd(), draws, new FittedModel.NoEffectTest(observed, pValue, draws.length), m.diagnostics());
    }

    private static double[] standardErrors(double[][] draws, int graders) {
        double[] se = new double[graders];
        if (draws.length < 2) return se;
        for (int j = 0; j < graders; j++) {
            double mean = 0, ss = 0;
            for (double[] d : draws) mean += d[j];
            mean /= draws.length;
            for (double[] d : draws) ss += (d[j] - mean) * (d[j] - mean);
            se[j] = Math.sqrt(ss / (draws.length - 1));
        }
        return se;
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
     * the answers showed no difference between the piles. The assumption is then supported, not proved, and
     * on real double-graded answers the additive marks model misses by about the allowance when graders'
     * harshness varies with answer quality.
     */
    public static FittedModel withAllowance(FittedModel m, double allowance) {
        double[][] draws = new double[m.severityDraws().length][];
        for (int b = 0; b < draws.length; b++) draws[b] = m.severityDraws()[b].clone();
        double[] est = m.graders().estimates();
        ContentAnalysis.biasAware(draws, est, allowance);
        FacetEstimates g = m.graders();
        FacetEstimates widened = new FacetEstimates(g.facet(), g.labels(), est.clone(), standardErrors(draws, est.length), g.counts(), g.extreme());
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
