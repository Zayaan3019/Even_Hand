package in.ac.iitm.evenhand.core;

import java.util.List;
import java.util.Optional;

/**
 * One finding from the audit, shaped like a compiler diagnostic: a code, a
 * severity, a message in the instructor's terms, the elements it is about, and
 * where possible a fix.
 *
 * <p>The audit is a static analysis over a marking design. Like a type checker it
 * refuses to let ill-formed input reach the stage that would produce nonsense from
 * it, and like a good type checker it says which construct is at fault and what
 * would repair it &mdash; not merely that something is wrong.
 */
public record Diagnostic(Code code,
                         Severity severity,
                         String message,
                         List<String> elements,
                         Optional<String> fixIt) {

    public enum Severity {
        /** Estimation cannot proceed for the named elements. */
        ERROR,
        /** Estimation can proceed but the result is weaker than the reader expects. */
        WARNING,
        /** Something the instructor should know about the arrangement. */
        NOTE
    }

    public enum Code {
        /** No grader facet exists: one person marked everything. */
        EH001_SINGLE_MARKER,
        /** Graders fall in disjoint groups with no chain of shared ratings between them. */
        EH002_DISCONNECTED_GRADERS,
        /** A grader marked exactly one question, and that question only they marked,
         *  so their severity and its difficulty move together and only their sum is
         *  estimable. This is the question-wise failure. */
        EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
        /** The design is identified, but the information available about a severity
         *  is so thin that its interval will not support a decision. */
        EH004_SEVERITY_IMPRECISE,
        /** Nobody was marked twice; every link rests on shared students or questions. */
        EH005_NO_DOUBLE_MARKING,
        /** A student, question or grader has too few observations to be estimated. */
        EH006_EXTREME_OR_SPARSE_ELEMENT,
        /** Scores present that the declared maximum does not allow. */
        EH007_SCORE_OUT_OF_RANGE,
        /** The same (student, question) was marked more than once: a double-mark,
         *  which is good news and is treated as a linking observation. */
        EH008_DOUBLE_MARK_FOUND,
        /** No column declares what each question was marked out of, and the marks are
         *  not all 0/1, so the maximum cannot be established. It is not inferred from
         *  the highest mark observed, because nobody may have scored full marks and the
         *  guess would be biased downward on exactly the hardest questions. */
        EH010_MAXIMUM_NOT_DECLARED,
        /** A question's declared maximum is not the same on every row. Usually means
         *  the column holds something else - a running total, or a weighted mark. */
        EH011_MAXIMUM_INCONSISTENT,
        /** Severities are estimable only if the piles handed to each assistant are
         *  taken to have been alike in ability. The estimate is real; the assumption
         *  is real too, and is reported rather than absorbed silently. */
        EH009_RESTS_ON_EXCHANGEABILITY
    }

    public Diagnostic {
        elements = List.copyOf(elements);
    }

    public static Diagnostic error(Code code, String message, List<String> elements, String fixIt) {
        return new Diagnostic(code, Severity.ERROR, message, elements, Optional.ofNullable(fixIt));
    }

    public static Diagnostic warning(Code code, String message, List<String> elements, String fixIt) {
        return new Diagnostic(code, Severity.WARNING, message, elements, Optional.ofNullable(fixIt));
    }

    public static Diagnostic note(Code code, String message, List<String> elements) {
        return new Diagnostic(code, Severity.NOTE, message, elements, Optional.empty());
    }

    /** Renders as a compiler would: {@code EH003 error: ... | fix: ...} */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(code.name(), 0, 5).append(' ')
          .append(severity.name().toLowerCase()).append(": ").append(message);
        if (!elements.isEmpty()) {
            sb.append(" [").append(String.join(", ", elements)).append(']');
        }
        fixIt.ifPresent(f -> sb.append("\n      fix: ").append(f));
        return sb.toString();
    }
}
