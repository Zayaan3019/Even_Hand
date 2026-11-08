package in.ac.iitm.evenhand.ingest;

import in.ac.iitm.evenhand.core.AllocationEvidence;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads a course's marking into the platform, pseudonymising as it goes.
 *
 * <p>One row per marked response: student, question, assistant, score, and where
 * available the maximum available, the assessment, the script's place in that
 * assistant's sequence, and whether the mark is the one that counts. Columns are
 * located by header name rather than by position, because every instructor's export
 * names them differently and none of them will rearrange their spreadsheet for us.
 *
 * <p>This is the parser stage of a compiler and behaves like one: a malformed row does
 * not abort the file, it produces a diagnostic carrying the line number, and the run
 * continues so the instructor learns about all of his bad rows at once rather than one
 * per attempt.
 *
 * <p>Identifiers are replaced before any {@link Response} object exists, so no type
 * downstream of this class is capable of holding a roll number. The file is read in two
 * passes so that people can be numbered in keyed-digest order rather than in the order
 * the export happened to list them, and so that the real roll numbers can be reduced to
 * {@link AllocationEvidence} while they are still in memory.
 *
 * <h2>On what a question was marked out of</h2>
 *
 * <ul>
 *   <li>If no column declares the maximum, it is <strong>not</strong> inferred from the
 *       highest mark observed. Nobody may have scored full marks, and the guess would be
 *       biased downward hardest on exactly the questions that were hardest. The reader
 *       refuses and says so --- unless every mark is 0 or 1, in which case the paper is
 *       right-or-wrong and the maximum is not in doubt.</li>
 *   <li>If a column does declare it, the value is checked for being the same on every
 *       row of a question. A column headed "total" often holds a student's running total
 *       rather than the question's maximum, and inconsistency across rows is the
 *       signature of that mistake, so it is reported.</li>
 *   <li>Marks may be fractional. Each question is read in the finest steps it was
 *       actually marked in --- halves, quarters --- and those steps become its categories,
 *       so 2.5 out of 5 is five steps out of ten rather than a row thrown away.</li>
 * </ul>
 *
 * <h2>On which mark counts</h2>
 *
 * <p>A response marked twice is an asset: it links the two assistants. But only one of
 * the marks, or their average, reached the student's grade, and that decides who could
 * have been harmed by whose marking. A {@code role} column says which; without one, the
 * first mark in the file is taken as the mark of record and the reader says so.
 */
public final class CsvIngest {

    /** Header names recognised for each column, lower-cased with spaces and hyphens folded. */
    static final Map<String, List<String>> ALIASES = Map.of(
            "student",    List.of("student", "student_id", "studentid", "roll", "roll_no", "rollno",
                                  "roll_number", "candidate", "student_roll",
                                  // Online exams: an LMS's ID number or username, a form's e-mail address.
                                  "id_number", "username", "email_address", "email", "email_id"),
            "question",   List.of("question", "question_id", "questionid", "item", "q", "part"),
            "grader",     List.of("grader", "grader_id", "marker", "assistant", "ta", "rater", "evaluator",
                                  "graded_by", "marked_by", "evaluated_by", "checked_by", "grader_name"),
            "score",      List.of("score", "marks", "mark", "points", "awarded", "marks_awarded"),
            // Deliberately excludes "total". In a marks export that far more often means
            // the student's total across questions than this question's maximum, and
            // reading one as the other corrupts every estimate with no visible symptom.
            "maxscore",   List.of("max", "max_score", "maxscore", "max_marks", "maxmarks", "max_mark",
                                  "out_of", "outof", "maximum", "question_max"),
            "assessment", List.of("assessment", "exam", "quiz", "paper", "session", "component"),
            "position",   List.of("position", "order", "sequence", "seq", "pile_position", "index"),
            "role",       List.of("role", "mark_role", "of_record", "record", "counts", "mark_type"),
            // What the student wrote. Optional: with it, answers that say the same thing can be
            // compared across assistants (eh-content); without it, nothing else changes.
            "answer",     List.of("answer", "answer_text", "response", "response_text", "student_answer",
                                  "written_answer", "text", "answer_script"));

    /** Roll numbers that mark the instructor's answer key rather than a student. */
    private static final Set<String> KEY_WORDS = Set.of("key", "answer_key", "answerkey", "model_answer", "model",
            "reference", "reference_answer", "solution", "solutions", "marking_scheme", "scheme");

    /** Whether a roll-number cell names the answer key. */
    static boolean isKey(String student) {
        return KEY_WORDS.contains(WideSheet.fold(student.replace("\"", "")));
    }

    private static final Set<String> RECORD_WORDS = Set.of(
            "record", "of_record", "primary", "official", "counts", "final", "yes", "y", "true", "1");
    private static final Set<String> SECOND_WORDS = Set.of(
            "second", "second_mark", "check", "moderation", "moderator", "audit", "review", "no", "n", "false", "0");

    /** Marks with more decimal places than this are not read as marks at all. */
    private static final int MAX_DECIMALS = 3;
    /** Steps finer than this per mark are unusual enough to say so. */
    private static final int FINE_STEPS = 20;
    /** Joins parts of a key; NUL cannot occur in a CSV field, so two keys can never run together. */
    private static final char SEPARATOR = (char) 0;

    private CsvIngest() {
    }

    /**
     * The outcome of reading a file: the marking, the design derived from it, the
     * diagnostics, and the key the instructor must keep.
     *
     * @param responses       every well-formed row
     * @param design          the same marking with the scores dropped, ready for the audit
     * @param diagnostics     parse problems, each naming its line
     * @param keyFileContents the pseudonymisation key, in memory only; write it only via
     *                        {@link #sealedKeyFile}
     * @param saltFingerprint safe to record in a run manifest
     * @param assessmentLabels assessment names in index order (not identifying), so a
     *                        course's weights can be given by name
     * @param answers         what the student wrote, parallel to {@code responses}: the empty
     *                        string where the file carries no text. Roll numbers, e-mail
     *                        addresses and TA names inside an answer are already replaced.
     * @param references      the instructor's model answer per question index, from a row whose
     *                        roll number reads KEY (or Answer key, Model answer, Solution); never a student
     */
    public record IngestResult(List<Response> responses,
                               MarkingDesign design,
                               List<Diagnostic> diagnostics,
                               String keyFileContents,
                               String saltFingerprint,
                               List<String> assessmentLabels,
                               List<String> answers,
                               Map<Integer, String> references) {

        public IngestResult {
            responses = List.copyOf(responses);
            diagnostics = List.copyOf(diagnostics);
            assessmentLabels = List.copyOf(assessmentLabels);
            answers = List.copyOf(answers);
            references = Map.copyOf(references);
            if (answers.size() != responses.size()) {
                throw new IllegalArgumentException("one answer (possibly empty) is needed per response");
            }
        }

        /** How many responses carry written text. */
        public int answerCount() {
            return (int) answers.stream().filter(a -> !a.isBlank()).count();
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }

        /** The key file sealed under the instructor's passphrase: the only form to write it in. */
        public byte[] sealedKeyFile(char[] passphrase) {
            return KeyFile.seal(keyFileContents, passphrase);
        }
    }

    private enum Role { RECORD, SECOND }

    /** A row as read, with its real identifiers. Never leaves this class. */
    private record RawRow(String student, String question, String grader, String assessment,
                          BigDecimal score, BigDecimal declaredMax, int position, Role role, int line,
                          String answer) {

        String unitKey() {
            return assessment + SEPARATOR + student + SEPARATOR + question;
        }

        /** Questions belong to their assessment: Q1 of a quiz is not Q1 of the end-semester exam. */
        String questionKey() {
            return assessment + SEPARATOR + question;
        }
    }

    /**
     * The pseudonymiser for a file: its salt comes from what the file <em>says</em> (who was given what by
     * whom for which answer), not from how it is laid out. Everything downstream is numbered and seeded by
     * the pseudonyms, so renaming a column, saving from Excel with other line endings or re-sorting the rows
     * cannot change a figure in the report, while a corrected mark still gets a new salt.
     */
    public static Pseudonymiser pseudonymiserFor(String csv) throws IOException {
        Pseudonymiser draft = Pseudonymiser.forInput(csv);
        IngestResult in = read(new BufferedReader(new java.io.StringReader(csv)), draft);
        Map<String, String> raw = draft.identifiersByLabel();
        List<String> studentLabels = in.design().studentLabels(), graderLabels = in.design().graderLabels();
        List<String> questionLabels = in.design().questionLabels();
        List<String> rows = new ArrayList<>(in.responses().size() + in.references().size());
        for (int i = 0; i < in.responses().size(); i++) {
            Response r = in.responses().get(i);
            rows.add(String.join("\u0001", raw.get(studentLabels.get(r.student())),
                    questionLabels.get(r.question()), raw.get(graderLabels.get(r.grader())),
                    r.score() + "/" + r.maxScore() + "/" + r.stepsPerMark(),
                    String.valueOf(r.cell().ofRecord()), String.valueOf(r.cell().position()), in.answers().get(i)));
        }
        in.references().forEach((q, key) -> rows.add(questionLabels.get(q) + "\u0001" + key));
        java.util.Collections.sort(rows);
        return Pseudonymiser.forInput(String.join("\u0002", rows));
    }

    public static IngestResult read(Path csv, Pseudonymiser pseudonymiser) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            return read(reader, pseudonymiser);
        }
    }

    /** How many lines below the first one the column headings are looked for. */
    private static final int TITLE_LINES = 10;

    /**
     * True if these fields are a marks table's column headings: a one-row-per-student sheet, or every column a
     * one-row-per-answer file needs. Headings that are recognised but faulty (a question with no maximum) count too,
     * so that their own message is the one the instructor sees.
     */
    static boolean isHeading(String[] fields) {
        try {
            if (WideSheet.detect(fields, ALIASES).isPresent()) return true;
            locateColumns(fields, new ArrayList<>());
            return true;
        } catch (IllegalArgumentException e) {
            return e.getMessage() == null || !e.getMessage().startsWith("no column found");
        }
    }

    /** True if the first lines of this CSV text hold a marks table's column headings. */
    public static boolean looksLikeMarks(String csv) {
        try {
            CsvRecords records = new CsvRecords(new BufferedReader(new StringReader(csv)));
            CsvRecords.Record r;
            int seen = 0;
            while (seen <= TITLE_LINES && (r = records.next()) != null) {
                if (r.isBlank()) continue;
                seen++;
                if (isHeading(r.fields())) return true;
            }
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    /** A mark or maximum as written; the exception's message is the text itself, quoted, for the instructor. */
    private static BigDecimal number(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new BadMark("'" + raw + "'");
        }
    }

    /** A mark or maximum that is not a number; its message is the text as written. */
    private static final class BadMark extends NumberFormatException {
        BadMark(String quoted) {
            super(quoted);
        }
    }

    public static IngestResult read(BufferedReader reader, Pseudonymiser pseudonymiser) throws IOException {
        List<Diagnostic> diagnostics = new ArrayList<>();

        // Records, not lines: a written answer in quotes may span several lines.
        CsvRecords records = new CsvRecords(reader);
        CsvRecords.Record headerRecord = records.next();
        while (headerRecord != null && headerRecord.isBlank()) headerRecord = records.next();
        if (headerRecord == null) {
            throw new IllegalArgumentException("the file is empty; expected a header row");
        }
        // A sheet often opens with a title ("CE3020 Quiz 1 marks") above the column headings: look a few lines down
        // for them. Lines read ahead and not needed are put back, so nothing is lost.
        java.util.ArrayDeque<CsvRecords.Record> pending = new java.util.ArrayDeque<>();
        if (!isHeading(headerRecord.fields())) {
            List<CsvRecords.Record> ahead = new ArrayList<>();
            CsvRecords.Record r;
            while (ahead.size() < TITLE_LINES && (r = records.next()) != null) if (!r.isBlank()) ahead.add(r);
            int found = -1;
            for (int i = 0; i < ahead.size() && found < 0; i++) if (isHeading(ahead.get(i).fields())) found = i;
            if (found < 0) {
                pending.addAll(ahead);
            } else {
                int titleEnd = ahead.get(found).line() - 1;
                diagnostics.add(Diagnostic.note(Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                        "The column headings are on line " + ahead.get(found).line() + "; line(s) " + headerRecord.line()
                        + (titleEnd > headerRecord.line() ? "-" + titleEnd : "") + " above them were read as a title "
                        + "and not used.", List.of()));
                headerRecord = ahead.get(found);
                pending.addAll(ahead.subList(found + 1, ahead.size()));
            }
        }
        String[] header = headerRecord.fields();
        if (header.length == 1 && header[0].indexOf(';') >= 0) {
            throw new IllegalArgumentException("this file separates its columns with semicolons (;), as Excel does in "
                    + "some regional settings. Upload the Excel workbook itself, or save it as \"CSV UTF-8 (Comma "
                    + "delimited)\".");
        }
        Optional<WideSheet> wide = WideSheet.detect(header, ALIASES);
        Map<String, Integer> columns = wide.isPresent() ? WideSheet.COLUMNS : locateColumns(header, diagnostics);
        if (wide.isPresent()) {
            diagnostics.add(Diagnostic.note(Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                    "Read as one row per student, with " + wide.get().questionCount() + " question column(s); "
                    + "each row's TA is taken to have marked that whole script."
                    + (wide.get().answeredQuestionCount() > 0
                        ? " Written answers were found for " + wide.get().answeredQuestionCount() + " of them."
                        : ""), List.of()));
        }

        List<RawRow> rows = new ArrayList<>();
        Map<String, String> keyAnswers = new LinkedHashMap<>();
        Map<String, Integer> firstLineOfUnit = new HashMap<>();
        CsvRecords.Record record;
        while ((record = pending.isEmpty() ? records.next() : pending.poll()) != null) {
            int lineNumber = record.line();
            if (record.isBlank()) {
                continue;
            }
            String[] fields = record.fields();
            for (String[] answer : wide.isPresent() ? wide.get().unpack(fields) : Collections.singletonList(fields)) {
                if (isKey(field(answer, columns.get("student")))) {
                    // The instructor's model answer: a reference for what a good answer says, never a student.
                    String text = columns.containsKey("answer") ? field(answer, columns.get("answer")).strip() : "";
                    String assessment = columns.containsKey("assessment") ? field(answer, columns.get("assessment")).strip() : "";
                    String question = field(answer, columns.get("question")).strip();
                    if (!text.isEmpty() && !question.isEmpty()) keyAnswers.put(assessment + SEPARATOR + question, text);
                    continue;
                }
                Optional<RawRow> parsed = parseRow(answer, columns, lineNumber, diagnostics);
                if (parsed.isEmpty()) {
                    continue;
                }
                RawRow row = parsed.get();
                rows.add(row);

                // A second mark on the same unit is not an error; it is the observation that
                // links two assistants, and it is what the audit most wants to find.
                Integer previousLine = firstLineOfUnit.putIfAbsent(row.unitKey(), row.line());
                if (previousLine != null) {
                    diagnostics.add(Diagnostic.note(
                            Diagnostic.Code.EH008_DOUBLE_MARK_FOUND,
                            "line " + row.line() + ": this response was already marked at line "
                            + previousLine + ". Treated as a second marking, which is what makes the "
                            + "two TAs comparable.",
                            List.of()));
                }
            }
        }

        boolean severalAssessments = rows.stream().map(RawRow::assessment).distinct().count() > 1;
        Map<String, String> questionNames = new LinkedHashMap<>();
        for (RawRow r : rows) {
            questionNames.putIfAbsent(r.questionKey(),
                    severalAssessments ? r.assessment() + ":" + r.question() : r.question());
        }

        Map<String, BigDecimal> maxima = resolveMaxima(rows, columns.containsKey("maxscore"),
                questionNames, diagnostics);
        if (maxima == null) {
            return new IngestResult(List.of(), MarkingDesign.of(List.of(), List.of(), List.of(), List.of()),
                    diagnostics, pseudonymiser.keyFileContents(), pseudonymiser.saltFingerprint(), List.of(),
                    List.of(), Map.of());
        }

        List<RawRow> valid = new ArrayList<>();
        for (RawRow r : rows) {
            BigDecimal max = maxima.get(r.questionKey());
            if (r.score().signum() < 0 || r.score().compareTo(max) > 0) {
                diagnostics.add(Diagnostic.error(
                        Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                        "line " + r.line() + ": score " + plain(r.score()) + " is outside 0.." + plain(max)
                        + " for question " + questionNames.get(r.questionKey()) + "; row skipped.",
                        List.of(),
                        "Either the mark is wrong or the maximum recorded for this question is."));
                continue;
            }
            valid.add(r);
        }

        Map<String, Integer> steps = stepsPerMark(valid, maxima, questionNames, diagnostics);
        Set<Integer> recordLines = marksOfRecord(valid, columns.containsKey("role"), diagnostics);

        // Assessments keep their order in the file, so they are listed as the course wrote them; their number feeds
        // no random draw. Questions are numbered in natural order of assessment and question name (Q2 before Q10),
        // and the rows are taken question by question, student by student, so the same
        // marks in any row order give the same analysis, draw for draw: every random draw is keyed to these numbers.
        Map<String, Integer> assessmentIndex = new LinkedHashMap<>();
        Map<String, Integer> questionIndex = new LinkedHashMap<>();
        List<String> questionLabels = new ArrayList<>();
        Set<String> rawStudents = new LinkedHashSet<>();
        Set<String> rawGraders = new LinkedHashSet<>();
        Map<String, String> questionAssessment = new HashMap<>();
        for (RawRow r : valid) {
            assessmentIndex.putIfAbsent(r.assessment(), assessmentIndex.size());
            questionAssessment.putIfAbsent(r.questionKey(), r.assessment());
            rawStudents.add(r.student());
            rawGraders.add(r.grader());
        }
        List<String> questionOrder = new ArrayList<>(questionAssessment.keySet());
        questionOrder.sort(Comparator.comparing((String k) -> questionAssessment.get(k), CsvIngest::natural)
                .thenComparing(k -> questionNames.get(k), CsvIngest::natural)
                .thenComparing(Comparator.naturalOrder()));
        for (String k : questionOrder) {
            questionIndex.put(k, questionIndex.size());
            questionLabels.add(questionNames.get(k));
        }

        // People are numbered in keyed-digest order, so a label reveals nothing about any list.
        Map<String, String> studentLabels = pseudonymiser.assignLabels(rawStudents, "Student ");
        Map<String, String> graderLabels = pseudonymiser.assignLabels(rawGraders, "Assistant ");
        Map<String, Integer> studentIndex = indexInOrder(studentLabels.keySet());
        Map<String, Integer> graderIndex = indexInOrder(graderLabels.keySet());
        valid = new ArrayList<>(valid);
        valid.sort(Comparator.comparingInt((RawRow r) -> questionIndex.get(r.questionKey()))
                .thenComparingInt(r -> studentIndex.get(r.student()))
                .thenComparingInt(r -> graderIndex.get(r.grader()))
                .thenComparingInt(RawRow::line));

        List<Response> responses = new ArrayList<>(valid.size());
        List<String> answers = new ArrayList<>(valid.size());
        AnswerScrubber scrubber = new AnswerScrubber(rawStudents, rawGraders);
        for (RawRow r : valid) {
            answers.add(r.answer() == null ? "" : scrubber.scrub(r.answer()));
            int perMark = steps.get(r.questionKey());
            DesignCell cell = new DesignCell(
                    studentIndex.get(r.student()),
                    questionIndex.get(r.questionKey()),
                    graderIndex.get(r.grader()),
                    assessmentIndex.get(r.assessment()),
                    r.position(),
                    recordLines.contains(r.line()));
            BigDecimal factor = BigDecimal.valueOf(perMark);
            responses.add(new Response(cell,
                    r.score().multiply(factor).intValueExact(),
                    maxima.get(r.questionKey()).multiply(factor).intValueExact(),
                    perMark));
        }

        AllocationEvidence evidence = allocationEvidence(responses, studentIndex, graderIndex.size());
        MarkingDesign design = MarkingDesign.fromResponses(responses,
                List.copyOf(studentLabels.values()), questionLabels, List.copyOf(graderLabels.values()))
                .withAllocationEvidence(evidence);

        if (scrubber.replacements() > 0) {
            diagnostics.add(Diagnostic.note(Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                    scrubber.replacements() + " roll number(s), e-mail address(es) or TA name(s) written inside "
                    + "answers were replaced by placeholders before the answers were read.", List.of()));
        }
        Map<Integer, String> references = new LinkedHashMap<>();
        for (Map.Entry<String, String> k : keyAnswers.entrySet()) {
            Integer q = questionIndex.get(k.getKey());
            if (q != null) references.put(q, k.getValue());
        }
        if (!keyAnswers.isEmpty()) {
            diagnostics.add(Diagnostic.note(Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                    "An answer key was found for " + references.size() + " question(s). It is used as a reference "
                    + "for what a good answer says, and is not counted as a student.", List.of()));
        }
        return new IngestResult(responses, design, diagnostics,
                pseudonymiser.keyFileContents(), pseudonymiser.saltFingerprint(),
                List.copyOf(assessmentIndex.keySet()), answers, references);
    }

    /**
     * Reads how the piles were dealt from the real roll numbers, while they are still in
     * memory, and keeps only the counts. Nothing here records where any one student
     * stands in the roll list.
     */
    private static AllocationEvidence allocationEvidence(List<Response> responses,
                                                         Map<String, Integer> studentIndex, int graders) {
        int n = studentIndex.size();
        List<String> byRoll = new ArrayList<>(studentIndex.keySet());
        byRoll.sort(RollNumbers.NATURAL_ORDER);
        int[] rank = new int[n];
        String[] department = new String[n];
        for (int i = 0; i < byRoll.size(); i++) {
            int s = studentIndex.get(byRoll.get(i));
            rank[s] = i;
            department[s] = RollNumbers.department(byRoll.get(i));
        }
        List<DesignCell> cells = responses.stream().map(Response::cell).toList();
        return AllocationEvidence.fromRollOrder(cells, n, graders, rank, department);
    }

    /** Text order with runs of digits compared as numbers, ignoring case: "Q2" before "Q10". */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String da = a.substring(si, i).replaceFirst("^0+(?=.)", ""), db = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int c = da.length() != db.length() ? Integer.compare(da.length(), db.length()) : da.compareTo(db);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static Map<String, Integer> indexInOrder(Set<String> keys) {
        Map<String, Integer> out = new HashMap<>();
        for (String k : keys) out.put(k, out.size());
        return out;
    }

    /**
     * Settles what each question was marked out of. Returns null when it cannot be
     * established, rather than a made-up denominator.
     */
    private static Map<String, BigDecimal> resolveMaxima(List<RawRow> rows,
                                                         boolean maxColumnPresent,
                                                         Map<String, String> questionNames,
                                                         List<Diagnostic> diagnostics) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return out;
        }
        if (maxColumnPresent) {
            Map<String, TreeSet<BigDecimal>> declared = new LinkedHashMap<>();
            for (RawRow r : rows) {
                declared.computeIfAbsent(r.questionKey(), q -> new TreeSet<>()).add(r.declaredMax());
            }
            for (Map.Entry<String, TreeSet<BigDecimal>> e : declared.entrySet()) {
                TreeSet<BigDecimal> values = e.getValue();
                out.put(e.getKey(), values.last());
                if (values.size() > 1) {
                    // The signature of a column holding something else - most often a
                    // student's running total. A warning rather than an error, but never silent.
                    String name = questionNames.get(e.getKey());
                    String listed = values.stream().map(CsvIngest::plain).collect(Collectors.joining(", ", "[", "]"));
                    diagnostics.add(Diagnostic.warning(
                            Diagnostic.Code.EH011_MAXIMUM_INCONSISTENT,
                            "Question " + name + " is recorded as marked out of " + listed + " on different "
                            + "rows. If that column holds a student's total rather than the question's maximum, "
                            + "every estimate for this question will be wrong without looking wrong. Using "
                            + plain(values.last()) + ".",
                            List.of(name),
                            "Check that the maximum column gives the marks available on the question, "
                            + "not a running total."));
                }
            }
            return out;
        }

        boolean rightOrWrong = rows.stream().allMatch(r ->
                r.score().compareTo(BigDecimal.ZERO) == 0 || r.score().compareTo(BigDecimal.ONE) == 0);
        if (rightOrWrong) {
            for (RawRow r : rows) {
                out.putIfAbsent(r.questionKey(), BigDecimal.ONE);
            }
            diagnostics.add(Diagnostic.note(
                    Diagnostic.Code.EH010_MAXIMUM_NOT_DECLARED,
                    "No column declares what each question was marked out of, but every mark is 0 or "
                    + "1, so the paper is read as right-or-wrong scoring.",
                    List.of()));
            return out;
        }
        // Refuse rather than guess. Taking the highest mark observed as the maximum
        // understates it on precisely the questions nobody answered well, which are the
        // questions the analysis is most likely to be asked about.
        BigDecimal highest = rows.stream().map(RawRow::score).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        diagnostics.add(Diagnostic.error(
                Diagnostic.Code.EH010_MAXIMUM_NOT_DECLARED,
                "No column declares what each question was marked out of, and the marks are not all 0 "
                + "or 1 (the highest seen is " + plain(highest) + "). The maximum is not guessed from the "
                + "highest mark awarded, because nobody may have scored full marks and the guess would be "
                + "most wrong on the hardest questions. No responses were loaded.",
                List.of(),
                "Add a column giving the marks available on each question - any of "
                + String.join(", ", ALIASES.get("maxscore")) + " is recognised."));
        return null;
    }

    /** The finest step each question was marked in, as steps per mark: 1, 2 for halves, 4 for quarters. */
    private static Map<String, Integer> stepsPerMark(List<RawRow> valid, Map<String, BigDecimal> maxima,
                                                     Map<String, String> questionNames,
                                                     List<Diagnostic> diagnostics) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : maxima.entrySet()) {
            out.put(e.getKey(), denominator(e.getValue()));
        }
        for (RawRow r : valid) {
            out.merge(r.questionKey(), denominator(r.score()), CsvIngest::lcm);
        }
        Set<String> used = valid.stream().map(RawRow::questionKey).collect(Collectors.toSet());
        for (Map.Entry<String, Integer> e : out.entrySet()) {
            if (e.getValue() > FINE_STEPS && used.contains(e.getKey())) {
                String name = questionNames.get(e.getKey());
                diagnostics.add(Diagnostic.warning(
                        Diagnostic.Code.EH015_FINE_MARK_STEPS,
                        "Marks on " + name + " were recorded in steps as fine as 1/" + e.getValue()
                        + " of a mark, and each step is treated as a category. Check that these are genuine "
                        + "part-marks rather than scaled or averaged values.",
                        List.of(name),
                        "Round to the steps the rubric actually awards, or rescale the question to whole marks."));
            }
        }
        return out;
    }

    /** The denominator of a decimal in lowest terms: 2 for 2.5, 4 for 0.25, 1 for 7. */
    private static int denominator(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(0, stripped.scale());
        BigInteger denominator = BigInteger.TEN.pow(scale);
        BigInteger numerator = stripped.movePointRight(scale).toBigIntegerExact();
        return denominator.divide(numerator.gcd(denominator)).intValueExact();
    }

    private static int lcm(int a, int b) {
        return a / BigInteger.valueOf(a).gcd(BigInteger.valueOf(b)).intValueExact() * b;
    }

    /**
     * Decides which rows are marks of record, and says so wherever it had to decide
     * rather than read.
     *
     * @return the line numbers of the rows whose marks count
     */
    private static Set<Integer> marksOfRecord(List<RawRow> valid, boolean roleColumn, List<Diagnostic> diagnostics) {
        Map<String, List<RawRow>> byUnit = new LinkedHashMap<>();
        for (RawRow r : valid) {
            byUnit.computeIfAbsent(r.unitKey(), k -> new ArrayList<>()).add(r);
        }
        Set<Integer> recordLines = new HashSet<>();
        int undeclared = 0;
        int noneMarked = 0;
        int averaged = 0;
        for (List<RawRow> unit : byUnit.values()) {
            List<RawRow> records = unit.stream().filter(r -> r.role() == Role.RECORD).toList();
            if (!records.isEmpty()) {
                records.forEach(r -> recordLines.add(r.line()));
                if (records.size() > 1) averaged++;
                continue;
            }
            List<RawRow> notSecond = unit.stream().filter(r -> r.role() != Role.SECOND).toList();
            if (notSecond.size() == 1) {
                recordLines.add(notSecond.get(0).line());
                continue;
            }
            RawRow chosen = notSecond.isEmpty() ? unit.get(0) : notSecond.get(0);
            recordLines.add(chosen.line());
            if (roleColumn) {
                noneMarked++;
            } else {
                undeclared++;
            }
        }
        String fix = "Add a column headed 'role' marking each row 'record' for the mark that counts, or "
                + "'second' for a check mark.";
        if (undeclared > 0) {
            diagnostics.add(Diagnostic.warning(
                    Diagnostic.Code.EH012_MARK_OF_RECORD_UNDECLARED,
                    undeclared + " response(s) were marked more than once and no column says which mark "
                    + "counts, so the first mark in the file is taken as the mark of record. Which mark "
                    + "counts decides who could have been affected by whose marking.",
                    List.of(), fix));
        }
        if (noneMarked > 0) {
            diagnostics.add(Diagnostic.warning(
                    Diagnostic.Code.EH012_MARK_OF_RECORD_UNDECLARED,
                    noneMarked + " response(s) have no row marked as the mark of record, so the first such "
                    + "mark in the file is used.",
                    List.of(), fix));
        }
        if (averaged > 0) {
            diagnostics.add(Diagnostic.note(
                    Diagnostic.Code.EH012_MARK_OF_RECORD_UNDECLARED,
                    averaged + " response(s) have more than one mark of record, and are read as the average "
                    + "of those marks, each TA carrying an equal share.",
                    List.of()));
        }
        return recordLines;
    }

    private static Optional<RawRow> parseRow(String[] fields,
                                             Map<String, Integer> columns,
                                             int lineNumber,
                                             List<Diagnostic> diagnostics) {
        try {
            String rawStudent = field(fields, columns.get("student")).strip();
            String rawQuestion = field(fields, columns.get("question")).strip();
            String rawGrader = field(fields, columns.get("grader")).strip();
            String rawScore = field(fields, columns.get("score")).strip();

            if (rawStudent.isEmpty() || rawQuestion.isEmpty() || rawGrader.isEmpty()) {
                diagnostics.add(Diagnostic.warning(
                        Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                        "line " + lineNumber + ": student, question or TA is blank; row skipped.",
                        List.of(), "Check the export for partly-filled rows."));
                return Optional.empty();
            }

            BigDecimal score = number(rawScore);
            BigDecimal declaredMax = null;
            if (columns.containsKey("maxscore")) {
                declaredMax = number(field(fields, columns.get("maxscore")).strip());
                if (declaredMax.signum() <= 0) {
                    diagnostics.add(Diagnostic.error(
                            Diagnostic.Code.EH011_MAXIMUM_INCONSISTENT,
                            "line " + lineNumber + ": a question cannot be marked out of " + plain(declaredMax)
                            + "; row skipped.",
                            List.of(), "Check the maximum column on this row."));
                    return Optional.empty();
                }
            }
            if (tooFine(score) || (declaredMax != null && tooFine(declaredMax))) {
                diagnostics.add(Diagnostic.error(
                        Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                        "line " + lineNumber + ": a mark with more than " + MAX_DECIMALS
                        + " decimal places is not a mark anyone awarded; row skipped.",
                        List.of(), "Export the marks as awarded, not as computed averages."));
                return Optional.empty();
            }

            String assessment = columns.containsKey("assessment")
                    ? field(fields, columns.get("assessment")).strip()
                    : "";
            int position = columns.containsKey("position")
                    ? parsePosition(field(fields, columns.get("position")))
                    : DesignCell.NO_POSITION;

            Role role = null;
            if (columns.containsKey("role")) {
                String rawRole = field(fields, columns.get("role")).strip();
                String folded = rawRole.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
                if (!folded.isEmpty()) {
                    if (RECORD_WORDS.contains(folded)) {
                        role = Role.RECORD;
                    } else if (SECOND_WORDS.contains(folded)) {
                        role = Role.SECOND;
                    } else {
                        diagnostics.add(Diagnostic.error(
                                Diagnostic.Code.EH012_MARK_OF_RECORD_UNDECLARED,
                                "line " + lineNumber + ": could not read '" + rawRole + "' as a mark's role; "
                                + "row skipped.",
                                List.of(), "Use 'record' for the mark that counts and 'second' for a check mark."));
                        return Optional.empty();
                    }
                }
            }

            String answer = columns.containsKey("answer") ? field(fields, columns.get("answer")) : null;
            return Optional.of(new RawRow(rawStudent, rawQuestion, rawGrader, assessment,
                    score, declaredMax, position, role, lineNumber, answer));

        } catch (BadMark e) {
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                    "line " + lineNumber + ": could not read " + e.getMessage() + " as a mark; row skipped.",
                    List.of(), e.getMessage().contains(",")
                            ? "Write decimals with a point: 7.5, not 7,5."
                            : "Marks must be numbers. Leave a mark blank, or write AB, if the question was not answered."));
            return Optional.empty();
        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                    "line " + lineNumber + ": could not read the marking position"
                    + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")") + "; row skipped.",
                    List.of(), "The order column must hold whole numbers: 1 for the first script marked, and so on."));
            return Optional.empty();
        } catch (ArrayIndexOutOfBoundsException e) {
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                    "line " + lineNumber + ": fewer columns than the header declares; row skipped.",
                    List.of(), "Check for stray commas inside unquoted fields."));
            return Optional.empty();
        }
    }

    private static boolean tooFine(BigDecimal value) {
        return value.stripTrailingZeros().scale() > MAX_DECIMALS;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static int parsePosition(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return DesignCell.NO_POSITION;
        }
        int position = Integer.parseInt(trimmed);
        if (position < 0) {
            throw new NumberFormatException("a marking position cannot be negative");
        }
        return position;
    }

    private static Map<String, Integer> locateColumns(String[] header, List<Diagnostic> diagnostics) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            // Instructors head columns "Roll No", "roll-no" and "roll_no" interchangeably,
            // so spaces and hyphens are folded to underscores before matching.
            String name = header[i].strip().toLowerCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_').replaceAll("_+", "_");
            for (Map.Entry<String, List<String>> entry : ALIASES.entrySet()) {
                if (entry.getValue().contains(name) && !found.containsKey(entry.getKey())) {
                    found.put(entry.getKey(), i);
                }
            }
        }
        for (String required : List.of("student", "question", "grader", "score")) {
            if (!found.containsKey(required)) {
                throw new IllegalArgumentException(
                        "no column found for '" + required + "'. Recognised names: "
                        + String.join(", ", ALIASES.get(required))
                        + ". (A sheet with one row per student needs a roll number column, a TA column, "
                        + "and each question's maximum in its header, like Q1 (10).)");
            }
        }
        if (!found.containsKey("position")) {
            diagnostics.add(Diagnostic.note(
                    Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                    "No column records the order scripts were marked in, so whether marks drift "
                    + "as a TA works through a pile cannot be tested for this course.",
                    List.of()));
        }
        return found;
    }

    private static String field(String[] fields, Integer index) {
        return index == null || index >= fields.length ? "" : fields[index];
    }
}
