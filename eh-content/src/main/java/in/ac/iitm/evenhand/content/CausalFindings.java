package in.ac.iitm.evenhand.content;

import java.util.List;
import java.util.Optional;

/**
 * Why did the marks differ? The causal reading of the content analysis.
 *
 * <p>The question is counterfactual: what would this answer have been given by another
 * assistant? Each answer is a unit, the assistant who marked it is the "treatment", and the
 * mark it would have received from each assistant is its potential outcome. An assistant
 * cannot change what a student wrote, and what a student wrote is the only thing the mark is
 * supposed to depend on; so once two answers say the same thing, the assistant is the only
 * remaining legitimate-looking reason for their marks to differ. That is the identifying
 * assumption (conditional ignorability given the answer), and every check below either
 * supports it or measures how far it would have to fail:
 *
 * <ol>
 *   <li>{@link #decomposition}: each assistant's raw difference from the class, split into
 *       what their students wrote (legitimate) and how they marked it (the assistant's effect).</li>
 *   <li>{@link #overlap}: whether each assistant's answers have comparable answers marked by
 *       others (positivity), and whether matching removed the content difference (balance).</li>
 *   <li>{@link #doubleMarked}: the strongest evidence there is: the very same script marked by
 *       two assistants, where the content is identical by construction.</li>
 *   <li>{@link #placebo}: the analysis rerun with assistant labels shuffled among similar answers;
 *       a method that finds effects there would be finding artefacts.</li>
 *   <li>{@link #sensitivity}: how much quality the embedding would have to miss, systematically,
 *       to explain an assistant's effect away (Rosenbaum bounds on matched pairs).</li>
 *   <li>{@link #mechanisms}: what kind of difference it is: a consistent severity, a different
 *       reading of one question's rubric, drift, isolated slips, or chance.</li>
 *   <li>{@link #variance}: of all the variation in marks, how much was what students wrote,
 *       who marked it, and what neither explains.</li>
 * </ol>
 */
public record CausalFindings(List<Decomposition> decomposition,
                             List<Overlap> overlap,
                             Optional<DoubleMarked> doubleMarked,
                             Placebo placebo,
                             List<Sensitivity> sensitivity,
                             List<Mechanism> mechanisms,
                             VarianceShare variance) {

    public CausalFindings {
        decomposition = List.copyOf(decomposition);
        overlap = List.copyOf(overlap);
        sensitivity = List.copyOf(sensitivity);
        mechanisms = List.copyOf(mechanisms);
    }

    /**
     * One assistant's raw difference from the class, in percentage points of the question
     * (positive = their students got higher marks than the class on the same questions).
     *
     * @param rawGap      how much higher (or lower) this pile's marks were
     * @param contentGap  how much of that the answers themselves explain: the pile's answers were
     *                    this much better (or worse) by the content model
     * @param markingGap  how much the assistant's marking added: minus their severity
     * @param unexplained what neither accounts for (chance, rubric ambiguity)
     */
    public record Decomposition(String assistant, int answers, double rawGap, double contentGap,
                                double markingGap, double unexplained) {}

    /**
     * Positivity and balance for one assistant.
     *
     * @param matchedShare share of their answers with a comparable answer marked by someone else
     * @param balanceBefore standardised difference in answer quality between their pile and the
     *                      others' before matching (|d| above 0.1 is a real difference)
     * @param balanceAfter  the same within matched pairs, after the content correction: near 0
     *                      means the comparison is like for like
     * @param lengthAfter   standardised difference in answer length within matched pairs: a
     *                      negative control, since an assistant cannot change how much was written
     */
    public record Overlap(String assistant, double matchedShare, double balanceBefore, double balanceAfter,
                          double lengthAfter) {}

    /**
     * Severities from scripts marked twice, by two assistants: identical content by construction.
     *
     * @param severity    per assistant (NaN where they marked no such script)
     * @param pairs       double-marked answers per assistant
     * @param agreement   correlation with the matched-answer severities, over assistants with both
     */
    public record DoubleMarked(int scripts, double[] severity, int[] pairs, double agreement) {}

    /**
     * @param pValue   share of label shuffles (among answers alike in content) whose severity spread
     *                 reached the observed one: the falsification test
     * @param shuffles how many were run
     * @param meanSpreadUnderShuffle the spread a method finds when there is nothing to find
     */
    public record Placebo(double pValue, int shuffles, double meanSpreadUnderShuffle, double observedSpread) {}

    /**
     * Rosenbaum sensitivity for one assistant.
     *
     * @param pairs      independent matched pairs (one per answer)
     * @param direction  +1 if the pairs say harsh, -1 lenient
     * @param pAtOne     sign-test p-value with no hidden bias
     * @param gamma      the largest hidden bias (odds that one answer of a pair was secretly the
     *                   better one) under which the conclusion still holds at 5%; 1 means it does
     *                   not hold even without hidden bias
     */
    public record Sensitivity(String assistant, int pairs, int direction, double pAtOne, double gamma) {}

    /** What kind of difference an assistant's marking shows. */
    public enum Kind {
        /** Harsher or more lenient by a similar amount on every question. */
        CONSISTENT_SEVERITY,
        /** The difference is concentrated in one or two questions: a different reading of the rubric. */
        QUESTION_SPECIFIC,
        /** Marks moved as the assistant worked through the pile. */
        DRIFT,
        /** No overall difference, but a few marks far out of line with similar answers. */
        ISOLATED_SLIPS,
        /** Nothing that chance would not produce. */
        NO_CLEAR_DIFFERENCE
    }

    /**
     * @param kind          the reading, from the tests below
     * @param byQuestion    the assistant's effect on each question they marked, with its standard error
     * @param heterogeneityP Cochran's Q test that the effect is the same on every question
     * @param slips         marks of theirs flagged as out of line
     * @param explanation   the reading in one sentence
     */
    public record Mechanism(String assistant, Kind kind, List<QuestionEffect> byQuestion, double heterogeneityP,
                            int slips, String explanation) {}

    public record QuestionEffect(String question, double severity, double standardError, int pairs) {}

    /**
     * Shares of the within-question variance in marks, in percent, summing to 100.
     *
     * @param content    what the students wrote (the legitimate part)
     * @param assistant  who marked it
     * @param residual   neither: chance, rubric ambiguity, what the embedding misses
     */
    public record VarianceShare(double content, double assistant, double residual) {}
}
