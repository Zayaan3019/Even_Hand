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
 * question, and a column saying which TA marked the script. Online exams export the same
 * shape, so this also reads an LMS quiz (Moodle: {@code Q. 1 /10.00}, {@code Grade/80.00}) and a
 * Google Forms quiz ({@code Score} written {@code 31 / 40}).
 *
 * <pre>
 *   roll_no,  name,  ta,     Q1 (10), Q1 answer,              Q2 (10), Total
 *   CE26B001, Asha,  Ravi,   7,       "The moment is max...", 8.5,     15.5
 * </pre>
 *
 * <p>Each question's maximum is written in its header, in brackets or after a slash, because
 * it is never guessed from the marks ({@link CsvIngest}). Columns that are not questions
 * (name, total, grade) are ignored. A blank cell, or one reading AB, is a question the student
 * did not answer, not an error.
 *
 * <p><b>Written answers.</b> A column headed like its question plus "answer", "response" or
 * "text" ({@code Q1 answer}, {@code Q1 (answer)}, {@code Answer Q1}, Moodle's {@code Response 1})
 * carries what the student wrote for that question. In a Google Forms quiz, which exports only
 * the total, every column that is not a known field is a question's answer, and the answers
 * are joined into one text for the whole response.
 *
 * <p>Each row is unpacked into the one-row-per-answer form the rest of ingest reads, keeping
 * its own line number so diagnostics point at the instructor's row.
 */
final class WideSheet {

    /** Column positions in the unpacked row. */
    static final Map<String, Integer> COLUMNS = Map.of(
            "student", 0, "question", 1, "grader", 2, "score", 3, "maxscore", 4, "assessment", 5, "answer", 6);

    /** "Q1 (10)", "Q1 [10]", "Q1 /10", "Q1 (out of 10)", Moodle's "Q. 1 /10.00". */
    private static final Pattern QUESTION = Pattern.compile(
            "^(.*?\\S)\\s*(?:\\(\\s*(?:out\\s+of\\s+)?(\\d+(?:\\.\\d+)?)\\s*\\)|\\[\\s*(\\d+(?:\\.\\d+)?)\\s*]"
            + "|/\\s*(\\d+(?:\\.\\d+)?))\\s*$", Pattern.CASE_INSENSITIVE);

    /** "Q1 answer", "Q1 (answer)", "Q1 - response", "Answer Q1", "Response 1", "Q1 text". */
    private static final Pattern ANSWER_AFTER = Pattern.compile(
            "^(.*?\\S)\\s*[-:_]?\\s*\\(?\\s*(?:answer|answers|response|text|written answer|answer text)\\s*\\)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_BEFORE = Pattern.compile(
            "^(?:answer|response|text)\\s*(?:to\\s+|for\\s+)?[-:_]?\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /** Headers that carry a maximum but are sums of questions, not questions (an LMS's "Grade/80.00"). */
    private static final Set<String> NOT_QUESTIONS = Set.of(
            "total", "grand_total", "sum", "overall", "aggregate", "final", "course_total", "marks", "percentage",
            "grade", "score", "total_score", "points", "total_points", "marks_obtained", "total_marks");

    /** Cell values that mean the question was not answered, or not marked yet. */
    private static final Set<String> NOT_ANSWERED = Set.of("", "ab", "absent", "-", "na", "n/a", "nil",
            "requires grading", "not yet graded", "needs grading");

    /** A Google Forms quiz score: "31 / 40". */
    private static final Pattern SCORE_OUT_OF = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)$");

    /** An institute e-mail address whose local part is a roll number: ce24b013@smail.iitm.ac.in. */
    private static final Pattern ROLL_EMAIL = Pattern.compile("^([a-z]{2}\\d{2}[a-z]\\d{3})@.*$", Pattern.CASE_INSENSITIVE);

    /** Where a Google Forms export keeps the quiz score, when no question column gives its own maximum. */
    private static final List<String> SCORE_COLUMNS = List.of("score", "total_score");

    /** Columns of an online-exam export that are about the attempt, not answers to a question. */
    private static final Set<String> NOT_ANSWERS = Set.of(
            "timestamp", "time", "date", "name", "full_name", "student_name", "surname", "first_name", "last_name",
            "section", "slot", "batch", "department", "dept", "programme", "program", "state", "status",
            "started_on", "completed", "time_taken", "institution", "remarks", "comments", "feedback", "role");

    private final int student;
    private final int grader;
    private final int assessment;
    private final List<String> questions;
    private final List<String> maxima;
    private final List<Integer> questionColumns;
    /** Per question, the columns holding its written answer (several only for a Google Forms quiz). */
    private final List<List<Integer>> answerColumns;
    /** True when the only marks are a Google Forms "Score" column, each cell "marks / maximum". */
    private final boolean scoreOutOf;

    private WideSheet(int student, int grader, int assessment, List<String> questions, List<String> maxima,
                      List<Integer> questionColumns, List<List<Integer>> answerColumns, boolean scoreOutOf) {
        this.student = student;
        this.grader = grader;
        this.assessment = assessment;
        this.questions = List.copyOf(questions);
        this.maxima = List.copyOf(maxima);
        this.questionColumns = List.copyOf(questionColumns);
        this.answerColumns = answerColumns.stream().map(List::copyOf).toList();
        this.scoreOutOf = scoreOutOf;
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
        int assessment = find(header, aliases.get("assessment"));

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
        int score = find(header, SCORE_COLUMNS);
        if (questions.isEmpty() && score >= 0) {
            // A Google Forms quiz export: one "Score" per student, written "31 / 40", and the
            // answers to every question in the columns that are not known fields.
            List<Integer> answers = new ArrayList<>();
            for (int i = 0; i < header.length; i++) {
                if (i == student || i == grader || i == score || i == assessment) continue;
                if (isKnownField(header[i], aliases)) continue;
                answers.add(i);
            }
            return Optional.of(new WideSheet(student, grader, assessment,
                    List.of("Score"), List.of(""), List.of(score), List.of(answers), true));
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("This looks like a sheet with one row per student, but no column "
                    + "says what its question was marked out of. Write each question's maximum in its header, "
                    + "like Q1 (10), Q2 (15) or Q3 /20, and try again.");
        }
        return Optional.of(new WideSheet(student, grader, assessment, questions, maxima, columns,
                answerColumnsFor(header, questions, columns), false));
    }

    /** Pairs each question with the column holding its written answer, if there is one. */
    static List<List<Integer>> answerColumnsFor(String[] header, List<String> questions, List<Integer> markColumns) {
        List<List<Integer>> out = new ArrayList<>();
        for (int k = 0; k < questions.size(); k++) out.add(new ArrayList<>());
        for (int i = 0; i < header.length; i++) {
            if (markColumns.contains(i)) continue;
            String h = header[i].strip();
            String key = null;
            Matcher after = ANSWER_AFTER.matcher(h);
            Matcher before = ANSWER_BEFORE.matcher(h);
            if (after.matches()) key = after.group(1);
            else if (before.matches()) key = before.group(1);
            if (key == null) continue;
            int k = matchQuestion(key, questions);
            if (k >= 0 && out.get(k).isEmpty()) out.get(k).add(i);
        }
        return out;
    }

    /** The question a label names: by its folded label first ("Q1" = "q.1"), then by number ("1" = "Q. 1"). */
    private static int matchQuestion(String label, List<String> questions) {
        String key = alnum(label);
        for (int k = 0; k < questions.size(); k++) if (alnum(questions.get(k)).equals(key)) return k;
        String number = trailingNumber(label);
        if (number == null) return -1;
        int found = -1;
        for (int k = 0; k < questions.size(); k++) {
            if (number.equals(trailingNumber(questions.get(k)))) {
                if (found >= 0) return -1;   // ambiguous: Quiz 1 Q1 and End-sem Q1
                found = k;
            }
        }
        return found;
    }

    private static String alnum(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String trailingNumber(String s) {
        Matcher m = Pattern.compile("(\\d+)\\D*$").matcher(s);
        return m.find() ? String.valueOf(Integer.parseInt(m.group(1))) : null;
    }

    private static boolean isKnownField(String header, Map<String, List<String>> aliases) {
        String f = fold(header);
        if (NOT_ANSWERS.contains(f)) return true;
        for (List<String> names : aliases.values()) if (names.contains(f)) return true;
        return false;
    }

    /** One row of the sheet as one-row-per-answer rows; unanswered questions are left out. */
    List<String[]> unpack(String[] fields) {
        List<String[]> out = new ArrayList<>(questions.size());
        String who = cell(fields, student);
        if (who.isEmpty()) return out;    // an export's footer, such as an LMS's "Overall average" row
        Matcher email = ROLL_EMAIL.matcher(who);
        // (an answer-key row keeps every question, marked or not; ingest reads it as the reference)
        if (email.matches()) who = email.group(1).toUpperCase(Locale.ROOT);
        boolean key = CsvIngest.isKey(who);
        String ta = key ? "" : cell(fields, grader);
        String exam = assessment < 0 ? "" : cell(fields, assessment);
        for (int k = 0; k < questions.size(); k++) {
            String mark = cell(fields, questionColumns.get(k));
            if (!key && NOT_ANSWERED.contains(mark.toLowerCase(Locale.ROOT))) continue;
            String max = maxima.get(k);
            if (scoreOutOf) {
                Matcher m = SCORE_OUT_OF.matcher(mark);
                if (m.matches()) {
                    mark = m.group(1);
                    max = m.group(2);
                }
            }
            StringBuilder answer = new StringBuilder();
            for (int col : answerColumns.get(k)) {
                String text = i(fields, col);
                if (text.isBlank()) continue;
                if (!answer.isEmpty()) answer.append('\n');
                answer.append(text.strip());
            }
            out.add(new String[] {who, questions.get(k), ta, mark, max, exam, answer.toString()});
        }
        return out;
    }

    int questionCount() {
        return questions.size();
    }

    /** How many questions have a column of written answers. */
    int answeredQuestionCount() {
        return (int) answerColumns.stream().filter(c -> !c.isEmpty()).count();
    }

    static String fold(String header) {
        return header.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_').replaceAll("_+", "_");
    }

    /** The first header matching a name, trying the names in order: a roll number beats an e-mail address. */
    private static int find(String[] header, List<String> names) {
        for (String name : names) {
            for (int i = 0; i < header.length; i++) if (fold(header[i]).equals(name)) return i;
        }
        return -1;
    }

    private static String cell(String[] fields, int i) {
        return i >= 0 && i < fields.length ? fields[i].strip() : "";
    }

    private static String i(String[] fields, int i) {
        return i >= 0 && i < fields.length ? fields[i] : "";
    }
}
