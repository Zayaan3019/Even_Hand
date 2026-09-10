package in.ac.iitm.evenhand.ingest;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads a course's marking into the platform, pseudonymising as it goes.
 *
 * <p>One row per marked response: student, question, assistant, score, and where
 * available the maximum available, the assessment, and the script's place in that
 * assistant's sequence. Columns are located by header name rather than by position,
 * because every instructor's export names them differently and none of them will
 * rearrange their spreadsheet for us.
 *
 * <p>This is the parser stage of a compiler and behaves like one: a malformed row does
 * not abort the file, it produces a diagnostic carrying the line number, and the run
 * continues so the instructor learns about all of his bad rows at once rather than one
 * per attempt.
 *
 * <p>Identifiers are replaced before any {@link Response} object exists, so no type
 * downstream of this class is capable of holding a roll number.
 *
 * <h2>On what a question was marked out of</h2>
 *
 * <p>The maximum matters more than it looks. Every estimate here is about where a mark
 * sits between nothing and full marks, so getting the denominator wrong does not add
 * noise --- it rescales a question's difficulty and, through it, the severity of
 * whoever marked it. Two rules follow, and both exist because the failure they prevent
 * is silent.
 *
 * <ul>
 *   <li>If no column declares the maximum, it is <strong>not</strong> inferred from the
 *       highest mark observed. Nobody may have scored full marks, and the guess would be
 *       biased downward hardest on exactly the questions that were hardest. The reader
 *       refuses and says so --- unless every mark is 0 or 1, in which case the paper is
 *       right-or-wrong and the maximum is not in doubt.</li>
 *   <li>If a column does declare it, the value is checked for being the same on every
 *       row of a question. A column headed "total" often holds a student's running total
 *       rather than the question's maximum, which would read seven marks out of ten as
 *       seven out of fifty-eight and quietly deflate that question. Inconsistency across
 *       rows is the signature of that mistake, so it is reported.</li>
 * </ul>
 */
public final class CsvIngest {

    /** Header names recognised for each column, lower-cased with spaces and hyphens folded. */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "student",    List.of("student", "student_id", "studentid", "roll", "roll_no", "rollno",
                                  "roll_number", "candidate", "student_roll"),
            "question",   List.of("question", "question_id", "questionid", "item", "q", "part"),
            "grader",     List.of("grader", "grader_id", "marker", "assistant", "ta", "rater", "evaluator"),
            "score",      List.of("score", "marks", "mark", "points", "awarded", "marks_awarded"),
            // Deliberately excludes "total". In a marks export that far more often means
            // the student's total across questions than this question's maximum, and
            // reading one as the other corrupts every estimate with no visible symptom.
            "maxscore",   List.of("max", "max_score", "maxscore", "max_marks", "maxmarks", "max_mark",
                                  "out_of", "outof", "maximum", "question_max"),
            "assessment", List.of("assessment", "exam", "quiz", "paper", "session", "component"),
            "position",   List.of("position", "order", "sequence", "seq", "pile_position", "index"));

    private CsvIngest() {
    }

    /**
     * The outcome of reading a file: the marking, the design derived from it, the
     * diagnostics, and the key the instructor must keep.
     *
     * @param responses       every well-formed row
     * @param design          the same marking with the scores dropped, ready for the audit
     * @param diagnostics     parse problems, each naming its line
     * @param keyFileContents the pseudonymisation key; the caller writes it somewhere the
     *                        application does not read, and nothing else uses it
     * @param saltFingerprint safe to record in a run manifest
     */
    public record IngestResult(List<Response> responses,
                               MarkingDesign design,
                               List<Diagnostic> diagnostics,
                               String keyFileContents,
                               String saltFingerprint) {

        public IngestResult {
            responses = List.copyOf(responses);
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }

    /** A row that parsed, before we know what its question was marked out of. */
    private record PendingRow(DesignCell cell, int score, int declaredMax, int line) {
        static final int MAX_UNKNOWN = -1;
    }

    public static IngestResult read(Path csv, Pseudonymiser pseudonymiser) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            return read(reader, pseudonymiser);
        }
    }

    public static IngestResult read(BufferedReader reader, Pseudonymiser pseudonymiser) throws IOException {
        List<Diagnostic> diagnostics = new ArrayList<>();

        String headerLine = reader.readLine();
        if (headerLine == null) {
            throw new IllegalArgumentException("the file is empty; expected a header row");
        }
        Map<String, Integer> columns = locateColumns(splitRow(headerLine), diagnostics);

        // Labels are assigned in order of first appearance and indexed densely, because
        // the audit works on integer indices and every downstream array is sized by them.
        Map<String, Integer> studentIndex = new LinkedHashMap<>();
        Map<String, Integer> questionIndex = new LinkedHashMap<>();
        Map<String, Integer> graderIndex = new LinkedHashMap<>();
        Map<String, Integer> assessmentIndex = new LinkedHashMap<>();

        List<PendingRow> pending = new ArrayList<>();
        Map<Long, Integer> seenCells = new HashMap<>();

        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            Optional<PendingRow> parsed = parseRow(splitRow(line), columns, lineNumber, pseudonymiser,
                    diagnostics, studentIndex, questionIndex, graderIndex, assessmentIndex);
            if (parsed.isEmpty()) {
                continue;
            }
            PendingRow row = parsed.get();
            pending.add(row);

            // A second mark on the same unit is not an error; it is the observation that
            // links two assistants, and it is what the audit most wants to find.
            Integer previousLine = seenCells.put(cellKey(row.cell()), row.line());
            if (previousLine != null) {
                diagnostics.add(Diagnostic.note(
                        Diagnostic.Code.EH008_DOUBLE_MARK_FOUND,
                        "line " + row.line() + ": this response was already marked at line "
                        + previousLine + ". Treated as a second marking, which is what makes the "
                        + "two assistants comparable.",
                        List.of()));
            }
        }

        List<Response> responses = resolveMaxima(pending, columns.containsKey("maxscore"),
                questionIndex, diagnostics);

        MarkingDesign design = MarkingDesign.fromResponses(responses,
                labelsOf(studentIndex), labelsOf(questionIndex), labelsOf(graderIndex));

        return new IngestResult(responses, design, diagnostics,
                pseudonymiser.keyFileContents(), pseudonymiser.saltFingerprint());
    }

    /**
     * Settles what each question was marked out of, and turns pending rows into
     * responses. Rows whose maximum cannot be established are dropped with an
     * explanation rather than given a made-up denominator.
     */
    private static List<Response> resolveMaxima(List<PendingRow> pending,
                                                boolean maxColumnPresent,
                                                Map<String, Integer> questionIndex,
                                                List<Diagnostic> diagnostics) {
        if (pending.isEmpty()) {
            return List.of();
        }
        Map<Integer, Integer> maxByQuestion = new TreeMap<>();
        List<String> questionLabels = labelsOf(questionIndex);

        if (maxColumnPresent) {
            Map<Integer, TreeSet<Integer>> declared = new TreeMap<>();
            for (PendingRow r : pending) {
                declared.computeIfAbsent(r.cell().question(), q -> new TreeSet<>()).add(r.declaredMax());
            }
            for (Map.Entry<Integer, TreeSet<Integer>> e : declared.entrySet()) {
                TreeSet<Integer> values = e.getValue();
                maxByQuestion.put(e.getKey(), values.last());
                if (values.size() > 1) {
                    // The signature of a column holding something else - most often a
                    // student's running total. A warning rather than an error, because a
                    // question can legitimately carry different maxima across assessments,
                    // but never silent.
                    diagnostics.add(Diagnostic.warning(
                            Diagnostic.Code.EH011_MAXIMUM_INCONSISTENT,
                            "Question " + questionLabels.get(e.getKey()) + " is recorded as marked out of "
                            + values + " on different rows. If that column holds a student's total rather "
                            + "than the question's maximum, every estimate for this question will be wrong "
                            + "without looking wrong. Using " + values.last() + ".",
                            List.of(questionLabels.get(e.getKey())),
                            "Check that the maximum column gives the marks available on the question, "
                            + "not a running total."));
                }
            }
        } else {
            int highestSeen = pending.stream().mapToInt(PendingRow::score).max().orElse(0);
            if (highestSeen <= 1) {
                for (PendingRow r : pending) {
                    maxByQuestion.put(r.cell().question(), 1);
                }
                diagnostics.add(Diagnostic.note(
                        Diagnostic.Code.EH010_MAXIMUM_NOT_DECLARED,
                        "No column declares what each question was marked out of, but every mark is 0 or "
                        + "1, so the paper is read as right-or-wrong scoring.",
                        List.of()));
            } else {
                // Refuse rather than guess. Taking the highest mark observed as the
                // maximum understates it on precisely the questions nobody answered well,
                // which are the questions the analysis is most likely to be asked about.
                diagnostics.add(Diagnostic.error(
                        Diagnostic.Code.EH010_MAXIMUM_NOT_DECLARED,
                        "No column declares what each question was marked out of, and marks run above 1 "
                        + "(the highest seen is " + highestSeen + "). The maximum is not guessed from the "
                        + "highest mark awarded, because nobody may have scored full marks and the guess "
                        + "would be most wrong on the hardest questions. No responses were loaded.",
                        List.of(),
                        "Add a column giving the marks available on each question - any of "
                        + String.join(", ", ALIASES.get("maxscore")) + " is recognised."));
                return List.of();
            }
        }

        List<Response> responses = new ArrayList<>(pending.size());
        for (PendingRow r : pending) {
            int max = maxByQuestion.getOrDefault(r.cell().question(), 1);
            if (r.score() < 0 || r.score() > max) {
                diagnostics.add(Diagnostic.error(
                        Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                        "line " + r.line() + ": score " + r.score() + " is outside 0.." + max
                        + " for question " + questionLabels.get(r.cell().question()) + "; row skipped.",
                        List.of(),
                        "Either the mark is wrong or the maximum recorded for this question is."));
                continue;
            }
            responses.add(new Response(r.cell(), r.score(), max));
        }
        return responses;
    }

    private static long cellKey(DesignCell c) {
        return ((long) c.assessment() << 42) ^ ((long) c.student() << 21) ^ c.question();
    }

    private static Optional<PendingRow> parseRow(String[] fields,
                                                 Map<String, Integer> columns,
                                                 int lineNumber,
                                                 Pseudonymiser pseudonymiser,
                                                 List<Diagnostic> diagnostics,
                                                 Map<String, Integer> studentIndex,
                                                 Map<String, Integer> questionIndex,
                                                 Map<String, Integer> graderIndex,
                                                 Map<String, Integer> assessmentIndex) {
        try {
            String rawStudent = field(fields, columns.get("student"));
            String rawQuestion = field(fields, columns.get("question"));
            String rawGrader = field(fields, columns.get("grader"));
            String rawScore = field(fields, columns.get("score"));

            if (rawStudent.isBlank() || rawQuestion.isBlank() || rawGrader.isBlank()) {
                diagnostics.add(Diagnostic.warning(
                        Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                        "line " + lineNumber + ": student, question or assistant is blank; row skipped.",
                        List.of(), "Check the export for partly-filled rows."));
                return Optional.empty();
            }

            int score = Integer.parseInt(rawScore.strip());
            int declaredMax = PendingRow.MAX_UNKNOWN;
            if (columns.containsKey("maxscore")) {
                declaredMax = Integer.parseInt(field(fields, columns.get("maxscore")).strip());
                if (declaredMax < 1) {
                    diagnostics.add(Diagnostic.error(
                            Diagnostic.Code.EH011_MAXIMUM_INCONSISTENT,
                            "line " + lineNumber + ": a question cannot be marked out of " + declaredMax
                            + "; row skipped.",
                            List.of(), "Check the maximum column on this row."));
                    return Optional.empty();
                }
            }

            // Pseudonymised here, before a Response exists. Nothing downstream of this
            // line is capable of holding the real identifier.
            int student = indexOf(studentIndex, pseudonymiser.labelFor(rawStudent, "Student "));
            int question = indexOf(questionIndex, rawQuestion.strip());
            int grader = indexOf(graderIndex, pseudonymiser.labelFor(rawGrader, "Assistant "));
            int assessment = columns.containsKey("assessment")
                    ? indexOf(assessmentIndex, field(fields, columns.get("assessment")).strip())
                    : 0;
            int position = columns.containsKey("position")
                    ? parsePosition(field(fields, columns.get("position")))
                    : DesignCell.NO_POSITION;

            return Optional.of(new PendingRow(
                    new DesignCell(student, question, grader, assessment, position),
                    score, declaredMax, lineNumber));

        } catch (NumberFormatException e) {
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                    "line " + lineNumber + ": could not read a number (" + e.getMessage()
                    + "); row skipped.",
                    List.of(), "Non-numeric marks such as 'absent' need their own column."));
            return Optional.empty();
        } catch (ArrayIndexOutOfBoundsException e) {
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                    "line " + lineNumber + ": fewer columns than the header declares; row skipped.",
                    List.of(), "Check for stray commas inside unquoted fields."));
            return Optional.empty();
        }
    }

    private static int parsePosition(String raw) {
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? DesignCell.NO_POSITION : Integer.parseInt(trimmed);
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
                        + String.join(", ", ALIASES.get(required)));
            }
        }
        if (!found.containsKey("position")) {
            diagnostics.add(Diagnostic.note(
                    Diagnostic.Code.EH006_EXTREME_OR_SPARSE_ELEMENT,
                    "No column records the order scripts were marked in, so whether marks drift "
                    + "as an assistant works through a pile cannot be tested for this course.",
                    List.of()));
        }
        return found;
    }

    private static String field(String[] fields, Integer index) {
        return index == null || index >= fields.length ? "" : fields[index];
    }

    private static int indexOf(Map<String, Integer> index, String label) {
        return index.computeIfAbsent(label, ignored -> index.size());
    }

    private static List<String> labelsOf(Map<String, Integer> index) {
        String[] out = new String[index.size()];
        index.forEach((label, i) -> out[i] = label);
        return List.of(out);
    }

    /** Minimal RFC-4180 splitting: quoted fields, doubled quotes inside them. */
    private static String[] splitRow(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out.toArray(new String[0]);
    }
}
