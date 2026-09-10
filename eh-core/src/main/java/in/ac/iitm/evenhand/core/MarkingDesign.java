package in.ac.iitm.evenhand.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The marking arrangement: which grader marked which question for which student,
 * with the scores deliberately absent.
 *
 * <p>A design carries a {@linkplain #fingerprint() fingerprint} over its incidence
 * structure. The fingerprint is what binds an {@link AuditVerdict} to the design it
 * was computed for, so a verdict obtained on one course cannot be presented as
 * evidence about another. See {@link AnchoredDesign}.
 */
public final class MarkingDesign {

    private final List<DesignCell> cells;
    private final List<String> studentLabels;
    private final List<String> questionLabels;
    private final List<String> graderLabels;
    private final int assessmentCount;
    private final long fingerprint;

    private MarkingDesign(List<DesignCell> cells,
                          List<String> studentLabels,
                          List<String> questionLabels,
                          List<String> graderLabels) {
        this.cells = List.copyOf(cells);
        this.studentLabels = List.copyOf(studentLabels);
        this.questionLabels = List.copyOf(questionLabels);
        this.graderLabels = List.copyOf(graderLabels);
        this.assessmentCount = 1 + this.cells.stream().mapToInt(DesignCell::assessment).max().orElse(0);
        this.fingerprint = computeFingerprint(this.cells,
                this.studentLabels.size(), this.questionLabels.size(), this.graderLabels.size());
        validateIndices();
    }

    public static MarkingDesign of(List<DesignCell> cells,
                                   List<String> studentLabels,
                                   List<String> questionLabels,
                                   List<String> graderLabels) {
        return new MarkingDesign(cells, studentLabels, questionLabels, graderLabels);
    }

    /**
     * Builds the design from marked responses by discarding every score.
     *
     * <p>This is the whole point of the type: the audit consumes what is left, so
     * an instructor can be told what his marking arrangement can support before he
     * shares a single mark.
     */
    public static MarkingDesign fromResponses(List<Response> responses,
                                              List<String> studentLabels,
                                              List<String> questionLabels,
                                              List<String> graderLabels) {
        List<DesignCell> cells = new ArrayList<>(responses.size());
        for (Response r : responses) {
            cells.add(r.cell());
        }
        return new MarkingDesign(cells, studentLabels, questionLabels, graderLabels);
    }

    private void validateIndices() {
        for (DesignCell c : cells) {
            if (c.student() >= studentLabels.size()) {
                throw new IllegalArgumentException("student index " + c.student() + " has no label");
            }
            if (c.question() >= questionLabels.size()) {
                throw new IllegalArgumentException("question index " + c.question() + " has no label");
            }
            if (c.grader() >= graderLabels.size()) {
                throw new IllegalArgumentException("grader index " + c.grader() + " has no label");
            }
        }
    }

    public List<DesignCell> cells()          { return cells; }
    public List<String> studentLabels()      { return studentLabels; }
    public List<String> questionLabels()     { return questionLabels; }
    public List<String> graderLabels()       { return graderLabels; }
    public int studentCount()                { return studentLabels.size(); }
    public int questionCount()               { return questionLabels.size(); }
    public int graderCount()                 { return graderLabels.size(); }
    public int assessmentCount()             { return assessmentCount; }
    public int size()                        { return cells.size(); }
    public boolean anyPositionRecorded()     { return cells.stream().anyMatch(DesignCell::hasPosition); }

    /**
     * A stable hash of the incidence structure. Order-independent, so two designs
     * that assign the same marking are the same design however the rows were sorted.
     */
    public long fingerprint() {
        return fingerprint;
    }

    private static long computeFingerprint(List<DesignCell> cells, int nS, int nQ, int nG) {
        // Order-independent: sum of per-cell mixes. Cheap, and collisions do not
        // matter for anything but the accidental-mismatch check it exists for.
        long acc = 0x9E3779B97F4A7C15L * (1L + nS) + 0xBF58476D1CE4E5B9L * (1L + nQ)
                 + 0x94D049BB133111EBL * (1L + nG) + cells.size();
        for (DesignCell c : cells) {
            long h = c.student();
            h = h * 1_000_003L + c.question();
            h = h * 1_000_003L + c.grader();
            h = h * 1_000_003L + c.assessment();
            h ^= (h >>> 31);
            h *= 0xFF51AFD7ED558CCDL;
            h ^= (h >>> 33);
            acc += h;
        }
        return acc;
    }

    /** Distinct graders who marked the given question. */
    public List<Integer> gradersOfQuestion(int question) {
        List<Integer> out = new ArrayList<>();
        for (DesignCell c : cells) {
            if (c.question() == question && !out.contains(c.grader())) {
                out.add(c.grader());
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Distinct questions marked by the given grader. */
    public List<Integer> questionsOfGrader(int grader) {
        List<Integer> out = new ArrayList<>();
        for (DesignCell c : cells) {
            if (c.grader() == grader && !out.contains(c.question())) {
                out.add(c.question());
            }
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MarkingDesign m && m.fingerprint == fingerprint && m.cells.equals(cells);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fingerprint, cells.size());
    }

    @Override
    public String toString() {
        return "MarkingDesign[" + studentCount() + " students, " + questionCount() + " questions, "
                + graderCount() + " graders, " + size() + " marks]";
    }
}
