package in.ac.iitm.evenhand.content;

import java.util.List;

/**
 * What the students' answers say about the marking.
 *
 * <p>Severities are in the same unit as the marks-only estimate: percentage points of a
 * question's maximum, positive = harsh, averaging zero within each group of assistants the
 * answers link. Every figure carries a bootstrap interval.
 *
 * @param status         whether the answers can compare the assistants, and if not why not
 * @param embedder       what turned the answers into vectors (model name and revision)
 * @param semantic       true for a language model, false for the lexical fallback
 * @param textCoverage   share of responses that carried written text
 * @param questions      per question: how many answers, how well content predicts the mark,
 *                       how many matched pairs
 * @param graderLabels   pseudonymised assistant labels, parallel to the arrays below
 * @param severity       content-anchored severity per assistant (0 when not linked)
 * @param standardError  standard error per assistant, including the bias allowance
 * @param samplingError  the bootstrap standard error alone, before the allowance: what tests use
 * @param pairCount      matched cross-assistant pairs involving each assistant
 * @param groups         assistants the answers link, each group on its own scale
 * @param draws          bootstrap replicates of the severities, {@code [draw][grader]}
 * @param pValue         chance that assistants who mark alike would differ this much
 * @param observedSpread RMS severity within groups, the statistic {@code pValue} is about
 * @param signal         answer-weighted mean cross-validated R^2 of the content model
 * @param matchedShare   share of answers with at least one matched answer by another assistant
 * @param sameAnswerDifferentMarks the starkest near-identical answers given different marks by different assistants
 * @param sameAssistantInconsistent near-identical answers one assistant marked differently
 * @param outOfLine      marks far from what similar answers received, after severity is allowed for
 * @param causal         why the marks differed: decomposition, identification checks,
 *                       falsification, sensitivity and mechanism (absent without links)
 * @param notes          plain-language caveats for the report
 */
public record ContentResult(Status status,
                            String embedder,
                            boolean semantic,
                            double textCoverage,
                            List<QuestionSummary> questions,
                            List<String> graderLabels,
                            double[] severity,
                            double[] standardError,
                            double[] samplingError,
                            int[] pairCount,
                            List<List<Integer>> groups,
                            double[][] draws,
                            double pValue,
                            double observedSpread,
                            double signal,
                            double matchedShare,
                            List<PairEvidence> sameAnswerDifferentMarks,
                            List<PairEvidence> sameAssistantInconsistent,
                            List<OutOfLine> outOfLine,
                            java.util.Optional<CausalFindings> causal,
                            List<String> notes) {

    public enum Status {
        /** The answers compare every linked assistant, and predict the marks well enough to trust it. */
        USABLE,
        /** Answers link the assistants, but say too little about the marks to compare them on. */
        WEAK_SIGNAL,
        /** No answer marked by one assistant resembles one marked by another (e.g. question-wise marking). */
        NO_LINKS,
        /** Too few responses carry written text. */
        NO_TEXT
    }

    public ContentResult {
        questions = List.copyOf(questions);
        graderLabels = List.copyOf(graderLabels);
        groups = groups.stream().map(List::copyOf).toList();
        sameAnswerDifferentMarks = List.copyOf(sameAnswerDifferentMarks);
        sameAssistantInconsistent = List.copyOf(sameAssistantInconsistent);
        outOfLine = List.copyOf(outOfLine);
        notes = List.copyOf(notes);
    }

    /** Whether one group holds every assistant, so all of them share one scale. */
    public boolean linksEveryAssistant() {
        return groups.size() == 1 && groups.get(0).size() == graderLabels.size() && graderLabels.size() >= 2;
    }

    public boolean usable() {
        return status == Status.USABLE;
    }

    /** The 2.5th and 97.5th percentiles of assistant {@code j}'s draws. */
    public double[] interval95(int j) {
        double[] column = new double[draws.length];
        for (int b = 0; b < column.length; b++) column[b] = draws[b][j];
        java.util.Arrays.sort(column);
        if (column.length == 0) return new double[] {severity[j], severity[j]};
        return new double[] {column[(int) Math.floor(0.025 * (column.length - 1))],
                             column[(int) Math.ceil(0.975 * (column.length - 1))]};
    }

    /**
     * @param label       the question as the report names it
     * @param answers     responses with written text
     * @param signal      cross-validated share of within-pile mark variation the content explains
     * @param pairs       matched pairs across assistants
     * @param assistants  how many assistants marked this question's answers
     */
    public record QuestionSummary(String label, int answers, double signal, int pairs, int assistants) {}

    /**
     * Two answers to one question that say nearly the same thing but were given different marks.
     *
     * @param similarity cosine of the two answers' embeddings
     * @param gapPoints  how far apart the marks are, in percentage points of the question
     */
    public record PairEvidence(String question, double maxMarks, double similarity, double gapPoints,
                               Side first, Side second) {}

    /** One side of a pair: who wrote it, who marked it, what it got and what it says. */
    public record Side(String student, String assistant, double marks, String excerpt) {}

    /**
     * A mark far from what answers like it received.
     *
     * @param expectedMarks what similar answers received, once severity is allowed for
     * @param z             robust z-score of the gap within the question
     */
    public record OutOfLine(String question, double maxMarks, String student, String assistant,
                            double marks, double expectedMarks, double z, String excerpt) {}
}
