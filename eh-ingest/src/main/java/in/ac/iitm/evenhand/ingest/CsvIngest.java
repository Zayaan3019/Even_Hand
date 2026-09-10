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

/**
 * Reads a course's marking into the platform, pseudonymising as it goes.
 *
 * <p>One row per marked response: student, question, assistant, score, and where
 * available the maximum available, the assessment, and the script's position in that
 * assistant's sequence. Columns are located by header name rather than by position,
 * because every instructor's export names them differently and none of them will
 * rearrange their spreadsheet for us.
 *
 * <p>This is the parser stage of a compiler, and it behaves like one: a malformed row
 * does not abort the file, it produces a diagnostic carrying the line number, and the
 * run continues so the instructor learns about all of his bad rows at once rather than
 * one per attempt.
 *
 * <p>Identifiers are replaced before any {@link Response} object exists, so no type
 * downstream of this class is capable of holding a roll number.
 */
public final class CsvIngest {

    /** Header names we recognise for each column, lower-cased. */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "student",    List.of("student", "student_id", "studentid", "roll", "roll_no", "rollno",
                                  "roll_number", "candidate", "student_roll"),
            "question",   List.of("question", "question_id", "questionid", "item", "q", "part"),
            "grader",     List.of("grader", "grader_id", "marker", "assistant", "ta", "rater", "evaluator"),
            "score",      List.of("score", "marks", "mark", "points", "awarded"),
            "maxscore",   List.of("max", "max_score", "maxscore", "max_marks", "maxmarks", "out_of", "total"),
            "assessment", List.of("assessment", "exam", "quiz", "paper", "session", "component"),
            "position",   List.of("position", "order", "sequence", "seq", "pile_position", "index"));

    private CsvIngest() {
    }

    /**
     * The outcome of reading a file: the marking, the design derived from it, the
     * diagnostics, and the key the instructor must keep.
     *
     * @param responses  every well-formed row
     * @param design     the same marking with the scores dropped, ready for the audit
     * @param diagnostics parse problems, each naming its line
     * @param keyFileContents the pseudonymisation key; the caller writes it somewhere
     *                        the application does not read, and nothing else uses it
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

        List<Response> responses = new ArrayList<>();
        Map<Long, Integer> seenCells = new HashMap<>();

        String line;
        int lineNumber = 1;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            String[] fields = splitRow(line);
            Optional<Response> parsed = parseRow(
                    fields, columns, lineNumber, pseudonymiser, diagnostics,
                    studentIndex, questionIndex, graderIndex, assessmentIndex);
            if (parsed.isEmpty()) {
                continue;
            }
            Response response = parsed.get();
            responses.add(response);

            // A second mark on the same unit is not an error; it is the observation that
            // links two assistants, and it is what the audit most wants to find.
            long cellKey = ((long) response.cell().assessment() << 42)
                    ^ ((long) response.student() << 21) ^ response.question();
            Integer previousLine = seenCells.put(cellKey, lineNumber);
            if (previousLine != null) {
                diagnostics.add(Diagnostic.note(
                        Diagnostic.Code.EH008_DOUBLE_MARK_FOUND,
                        "line " + lineNumber + ": this response was already marked at line "
                        + previousLine + ". Treated as a second marking, which is what makes the "
                        + "two assistants comparable.",
                        List.of()));
            }
        }

        MarkingDesign design = MarkingDesign.fromResponses(responses,
                labelsOf(studentIndex), labelsOf(questionIndex), labelsOf(graderIndex));

        return new IngestResult(responses, design, diagnostics,
                pseudonymiser.keyFileContents(), pseudonymiser.saltFingerprint());
    }

    private static Optional<Response> parseRow(String[] fields,
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
            int maxScore = columns.containsKey("maxscore")
                    ? Integer.parseInt(field(fields, columns.get("maxscore")).strip())
                    : 1;

            if (score < 0 || score > maxScore) {
                diagnostics.add(Diagnostic.error(
                        Diagnostic.Code.EH007_SCORE_OUT_OF_RANGE,
                        "line " + lineNumber + ": score " + score + " is outside 0.." + maxScore
                        + "; row skipped.",
                        List.of(),
                        "Either the mark is wrong or the maximum for this question is."));
                return Optional.empty();
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

            return Optional.of(new Response(
                    new DesignCell(student, question, grader, assessment, position), score, maxScore));

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
