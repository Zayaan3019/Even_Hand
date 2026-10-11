package in.ac.iitm.evenhand.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mark sheet most instructors already have: one row per student, one column per
 * question, and a column saying which TA marked the script.
 *
 * <pre>
 *   roll_no,  name,  ta,     Q1 (10), Q2 (10), Q3 (15), Total
 *   CE26B001, Asha,  Ravi,   7,       8.5,     12,      27.5
 * </pre>
 *
 * <p>Each question's maximum is written in its header, in brackets or after a slash, because
 * it is never guessed from the marks ({@link CsvIngest}). Columns that are not questions
 * (name, total, grade) are ignored. A blank cell, or one reading AB, is a question the student
 * did not answer, not an error. Each row is unpacked into the one-row-per-answer form the rest
 * of ingest reads, keeping its own line number so diagnostics point at the instructor's row.
 */
final class WideSheet {

    /** Column positions in the unpacked row. */
    static final Map<String, Integer> COLUMNS = Map.of(
            "student", 0, "question", 1, "grader", 2, "score", 3, "maxscore", 4, "assessment", 5);

    /** "Q1 (10)", "Q1 [10]", "Q1 /10", "Q1 (out of 10)". */
    private static final Pattern QUESTION = Pattern.compile(
            "^(.*?\\S)\\s*(?:\\(\\s*(?:out\\s+of\\s+)?(\\d+(?:\\.\\d+)?)\\s*\\)|\\[\\s*(\\d+(?:\\.\\d+)?)\\s*]"
            + "|/\\s*(\\d+(?:\\.\\d+)?))\\s*$", Pattern.CASE_INSENSITIVE);

    /** Headers that carry a maximum but are sums of questions, not questions. */
    private static final Set<String> NOT_QUESTIONS = Set.of(
            "total", "grand_total", "sum", "overall", "aggregate", "final", "course_total", "marks", "percentage");

    /** Cell values that mean the question was not answered. */
    private static final Set<String> NOT_ANSWERED = Set.of("", "ab", "absent", "-", "na", "n/a", "nil");

    private final int student;
    private final int grader;
    private final int assessment;
    private final List<String> questions;
    private final List<String> maxima;
    private final List<Integer> questionColumns;

    private WideSheet(int student, int grader, int assessment,
                      List<String> questions, List<String> maxima, List<Integer> questionColumns) {
        this.student = student;
        this.grader = grader;
        this.assessment = assessment;
        this.questions = List.copyOf(questions);
        this.maxima = List.copyOf(maxima);
        this.questionColumns = List.copyOf(questionColumns);
    }

    /**
     * The sheet's layout if the header is one row per student; empty if it is one row per
     * answer, which {@link CsvIngest} reads directly.
     *
     * @throws IllegalArgumentException if it is one row per student but no header gives a maximum
     */
    static Optional<WideSheet> detect(String[] header, Map<String, List<String>> aliases) {
        int student = find(header, aliases.get("student"));
        int grader = find(header, aliases.get("grader"));
        boolean answerRows = find(header, aliases.get("question")) >= 0 && find(header, aliases.get("score")) >= 0;
        if (answerRows || student < 0 || grader < 0) return Optional.empty();

        List<String> questions = new ArrayList<>();
        List<String> maxima = new ArrayList<>();
        List<Integer> columns = new ArrayList<>();
        for (int i = 0; i < header.length; i++) {
            Matcher m = QUESTION.matcher(header[i].strip());
            if (!m.matches() || NOT_QUESTIONS.contains(fold(m.group(1)))) continue;
            questions.add(m.group(1).strip());
            maxima.add(m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4));
            columns.add(i);
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("This looks like a sheet with one row per student, but no column "
                    + "says what its question was marked out of. Write each question's maximum in its header, "
                    + "like Q1 (10), Q2 (15) or Q3 /20, and try again.");
        }
        return Optional.of(new WideSheet(student, grader, find(header, aliases.get("assessment")),
                questions, maxima, columns));
    }

    /** One row of the sheet as one-row-per-answer rows; unanswered questions are left out. */
    List<String[]> unpack(String[] fields) {
        List<String[]> out = new ArrayList<>(questions.size());
        String who = cell(fields, student);
        String ta = cell(fields, grader);
        String exam = assessment < 0 ? "" : cell(fields, assessment);
        for (int k = 0; k < questions.size(); k++) {
            String mark = cell(fields, questionColumns.get(k));
            if (NOT_ANSWERED.contains(mark.toLowerCase(Locale.ROOT))) continue;
            out.add(new String[] {who, questions.get(k), ta, mark, maxima.get(k), exam});
        }
        return out;
    }

    int questionCount() {
        return questions.size();
    }

    static String fold(String header) {
        return header.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_').replaceAll("_+", "_");
    }

    private static int find(String[] header, List<String> names) {
        for (int i = 0; i < header.length; i++) if (names.contains(fold(header[i]))) return i;
        return -1;
    }

    private static String cell(String[] fields, int i) {
        return i < fields.length ? fields[i].strip() : "";
    }
}
