package in.ac.iitm.evenhand.ingest;

import in.ac.iitm.evenhand.core.AllocationEvidence;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Reading a course's marking in, and proving that nothing identifying survives it.
 *
 * <p>The fixtures use realistic IIT Madras roll numbers and real-looking names on
 * purpose. A privacy test that only ever sees the string {@code "student1"} proves
 * nothing; these tests search the entire output for the actual identifiers that went in.
 */
class CsvIngestTest {

    /** Deterministic salt so failures are reproducible. Never a constant in production. */
    private static final byte[] TEST_SALT = "even-hand-test-salt-not-for-real-use".getBytes(StandardCharsets.UTF_8);

    private static final String ROLL_1 = "CE23B092";
    private static final String ROLL_2 = "ME21B145";
    private static final String TA_1 = "Ananya Krishnan";
    private static final String TA_2 = "Rahul Venkatesh";

    private static final String WELL_FORMED = """
            roll_no,question,ta,marks,max_marks,exam,position
            CE23B092,Q1,Ananya Krishnan,7,10,quiz1,1
            CE23B092,Q2,Rahul Venkatesh,5,10,quiz1,1
            ME21B145,Q1,Ananya Krishnan,9,10,quiz1,2
            ME21B145,Q2,Rahul Venkatesh,8,10,quiz1,2
            """;

    private static CsvIngest.IngestResult ingest(String csv) throws IOException {
        return CsvIngest.read(new BufferedReader(new StringReader(csv)),
                Pseudonymiser.withSalt(TEST_SALT));
    }

    private static String csv(String header, List<String> lines) {
        return header + "\n" + String.join("\n", lines) + "\n";
    }

    /** identifier to label, read back from the instructor's key file. */
    private static Map<String, String> labelsInKey(String keyFileContents) {
        Map<String, String> out = new HashMap<>();
        boolean inRows = false;
        for (String line : keyFileContents.split("\n")) {
            if (line.equals("identifier,label,digest")) {
                inRows = true;
            } else if (inRows && !line.isBlank()) {
                String[] parts = line.split(",");
                out.put(parts[0], parts[1]);
            }
        }
        return out;
    }

    private static String evidence(CsvIngest.IngestResult result) {
        return result.design().allocationEvidence().map(AllocationEvidence::describe).orElseThrow();
    }

    @Test
    @DisplayName("a well-formed file yields one response per row and a design of the right shape")
    void readsAWellFormedFile() throws IOException {
        CsvIngest.IngestResult result = ingest(WELL_FORMED);

        assertThat(result.responses()).hasSize(4);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.design().studentCount()).isEqualTo(2);
        assertThat(result.design().questionCount()).isEqualTo(2);
        assertThat(result.design().graderCount()).isEqualTo(2);
        assertThat(result.responses().get(0).score()).isEqualTo(7);
        assertThat(result.responses().get(0).maxScore()).isEqualTo(10);
    }

    @Test
    @DisplayName("no real identifier survives ingest, anywhere except the instructor's key")
    void noRawIdentifierSurvivesIngest() throws IOException {
        CsvIngest.IngestResult result = ingest(WELL_FORMED);

        // Everything the rest of the platform can see, flattened into one string.
        String everythingDownstream = String.join("|",
                String.join("|", result.design().studentLabels()),
                String.join("|", result.design().graderLabels()),
                String.join("|", result.design().questionLabels()),
                result.responses().toString(),
                result.design().allocationEvidence().toString(),
                result.diagnostics().toString(),
                result.saltFingerprint());

        for (String identifier : List.of(ROLL_1, ROLL_2, TA_1, TA_2)) {
            assertThat(everythingDownstream)
                    .as("identifier '%s' must not survive ingest", identifier)
                    .doesNotContain(identifier);
        }

        // The one place they legitimately appear is the key file, which is sealed before
        // it is written and which the application never reads back.
        assertThat(result.keyFileContents()).contains(ROLL_1, ROLL_2, TA_1, TA_2);

        // Question labels are not identifying and are deliberately left readable, or the
        // instructor could not act on "Q2 was ambiguous".
        assertThat(result.design().questionLabels()).containsExactly("Q1", "Q2");
    }

    @Test
    @DisplayName("the same identifier maps to one pseudonym, and different ones never collide")
    void pseudonymsAreStableWithinARunAndDistinctBetweenPeople() throws IOException {
        CsvIngest.IngestResult result = ingest(WELL_FORMED);

        // Both of CE23B092's rows must land on the same student, or the model would treat
        // one person as two and every ability estimate would be halved in precision.
        assertThat(result.design().studentLabels()).containsExactly("Student 1", "Student 2");
        assertThat(result.design().graderLabels()).containsExactly("Assistant 1", "Assistant 2");
        assertThat(result.responses().get(0).student()).isEqualTo(result.responses().get(1).student());
        assertThat(result.responses().get(0).grader()).isNotEqualTo(result.responses().get(1).grader());
    }

    @Test
    @DisplayName("labels follow the keyed digests, so they reveal nothing about the file's order")
    void labelsDoNotRevealFileOrder() throws IOException {
        List<String> rolls = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String roll = String.format("CE23B%03d", i);
            rolls.add(roll);
            lines.add(roll + ",Q1," + TA_1 + ",5,10");
        }
        List<String> reversed = new ArrayList<>(lines);
        Collections.reverse(reversed);

        Map<String, String> forward = labelsInKey(ingest(csv("roll_no,question,ta,marks,max_marks", lines))
                .keyFileContents());
        Map<String, String> backward = labelsInKey(ingest(csv("roll_no,question,ta,marks,max_marks", reversed))
                .keyFileContents());

        // Numbering by first appearance made "Student 1" the first row of the export - in a
        // roll-sorted file, the lowest roll number - so anyone with the class list could undo
        // every pseudonym by counting. The order is now the keyed digests', whatever the file's.
        assertThat(forward).isEqualTo(backward);
        Pseudonymiser keyed = Pseudonymiser.withSalt(TEST_SALT);
        rolls.sort(Comparator.comparing(keyed::digestFor));
        for (int i = 0; i < rolls.size(); i++) {
            assertThat(forward.get(rolls.get(i))).isEqualTo("Student " + (i + 1));
        }
    }

    @Test
    @DisplayName("how the piles were dealt is read from the roll numbers, not from the order of the rows")
    void rollOrderEvidenceComesFromRollNumbers() throws IOException {
        // Alternate roll numbers to each assistant - a random-looking deal - but exported
        // sorted by assistant, which is how many teams keep their sheets.
        List<String> interleavedSortedByTa = new ArrayList<>();
        for (String ta : List.of(TA_1, TA_2)) {
            for (int i = 1; i <= 12; i++) {
                if ((i % 2 == 1) != ta.equals(TA_1)) continue;
                for (String q : List.of("Q1", "Q2")) {
                    interleavedSortedByTa.add(String.format("CE23B%03d,%s,%s,5,10", i, q, ta));
                }
            }
        }
        // Contiguous blocks of roll numbers, exported in a shuffled order.
        List<String> blocksShuffled = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            for (String q : List.of("Q1", "Q2")) {
                blocksShuffled.add(String.format("CE23B%03d,%s,%s,5,10", i, q, i <= 6 ? TA_1 : TA_2));
            }
        }
        Collections.shuffle(blocksShuffled, new Random(7));

        String header = "roll_no,question,ta,marks,max_marks";
        // Reading contiguity off the row order called the first a roll-number split and
        // could miss the second. The real roll numbers get both right.
        assertThat(evidence(ingest(csv(header, interleavedSortedByTa))))
                .contains("interleaved")
                .doesNotContain("contiguous block");
        assertThat(evidence(ingest(csv(header, blocksShuffled))))
                .contains("contiguous block of roll numbers");
    }

    @Test
    @DisplayName("piles that follow the department code in the roll number are flagged")
    void pilesFollowingDepartmentsAreFlagged() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            lines.add(String.format("CE23B%03d,Q1,%s,5,10", i, TA_1));
            lines.add(String.format("ME23B%03d,Q1,%s,5,10", i, TA_2));
        }

        // One assistant took the civil engineers and another the mechanical engineers, so
        // "the piles were alike" is exactly the assumption most worth doubting.
        assertThat(evidence(ingest(csv("roll_no,question,ta,marks,max_marks", lines))))
                .contains("department");
    }

    @Test
    @DisplayName("half and quarter marks are read in the steps they were awarded in, not thrown away")
    void fractionalMarksAreReadInTheirOwnSteps() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks
                CE23B092,Q1,Ananya Krishnan,2.5,5
                ME21B145,Q1,Ananya Krishnan,3,5
                CE23B092,Q2,Rahul Venkatesh,0.25,1
                ME21B145,Q2,Rahul Venkatesh,1,1
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // Half marks are routine in IIT Madras marking, and reading marks as integers
        // dropped every such row as unreadable.
        assertThat(result.hasErrors()).isFalse();
        Response half = result.responses().get(0);
        assertThat(half.score()).isEqualTo(5);
        assertThat(half.maxScore()).isEqualTo(10);
        assertThat(half.stepsPerMark()).isEqualTo(2);
        assertThat(half.marks()).isCloseTo(2.5, within(1e-12));
        assertThat(result.responses().get(1).score()).isEqualTo(6);
        Response quarter = result.responses().get(2);
        assertThat(quarter.stepsPerMark()).isEqualTo(4);
        assertThat(quarter.score()).isEqualTo(1);
        assertThat(quarter.maxScore()).isEqualTo(4);
    }

    @Test
    @DisplayName("a mark carrying more decimal places than anyone awards is refused")
    void computedValuesAreNotMarks() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks
                CE23B092,Q1,Ananya Krishnan,2.3333,5
                """;

        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.responses()).isEmpty();
        assertThat(result.diagnostics()).anySatisfy(d ->
                assertThat(d.message()).contains("line 2").contains("decimal places"));
    }

    @Test
    @DisplayName("a role column decides which of two marks counts")
    void aRoleColumnDecidesWhichMarkCounts() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks,role
                CE23B092,Q1,Ananya Krishnan,7,10,second
                CE23B092,Q1,Rahul Venkatesh,6,10,record
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // Both marks are observations of their assistants; only the one of record reached
        // the student's grade, and that is what decides who could have been affected.
        assertThat(result.responses()).hasSize(2);
        assertThat(result.responses().get(0).cell().ofRecord()).isFalse();
        assertThat(result.responses().get(1).cell().ofRecord()).isTrue();
        assertThat(result.diagnostics())
                .noneMatch(d -> d.code() == Diagnostic.Code.EH012_MARK_OF_RECORD_UNDECLARED);
    }

    @Test
    @DisplayName("without a role column the first mark counts, and the reader says it had to decide")
    void withoutARoleColumnTheFirstMarkCounts() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks
                CE23B092,Q1,Ananya Krishnan,7,10
                CE23B092,Q1,Rahul Venkatesh,6,10
                """;

        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.responses().get(0).cell().ofRecord()).isTrue();
        assertThat(result.responses().get(1).cell().ofRecord()).isFalse();
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(Diagnostic.Code.EH012_MARK_OF_RECORD_UNDECLARED);
            assertThat(d.severity()).isEqualTo(Diagnostic.Severity.WARNING);
            assertThat(d.fixIt().orElseThrow()).contains("role");
        });
    }

    @Test
    @DisplayName("two marks of record on one response are read as averaged")
    void twoMarksOfRecordAreAveraged() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks,role
                CE23B092,Q1,Ananya Krishnan,7,10,record
                CE23B092,Q1,Rahul Venkatesh,6,10,record
                """;

        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.responses()).allSatisfy(r -> assertThat(r.cell().ofRecord()).isTrue());
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(Diagnostic.Code.EH012_MARK_OF_RECORD_UNDECLARED);
            assertThat(d.message()).contains("average");
        });
    }

    @Test
    @DisplayName("a question belongs to its assessment: Q1 of a quiz is not Q1 of the end-semester exam")
    void questionsAreScopedToTheirAssessment() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks,exam
                CE23B092,Q1,Ananya Krishnan,7,10,quiz1
                CE23B092,Q1,Rahul Venkatesh,6,10,endsem
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // Merging them would give two different questions one difficulty, and would read
        // two separate marks as one response marked twice.
        assertThat(result.design().questionLabels()).containsExactly("quiz1:Q1", "endsem:Q1");
        assertThat(result.diagnostics()).noneMatch(d -> d.code() == Diagnostic.Code.EH008_DOUBLE_MARK_FOUND);
    }

    @Test
    @DisplayName("the key carries the course's salt, so a later run of the same course links to this one")
    void theKeyRestoresTheCourseSalt() throws IOException {
        CsvIngest.IngestResult result = ingest(WELL_FORMED);

        Pseudonymiser restored = Pseudonymiser.fromKeyFile(result.keyFileContents());

        assertThat(restored.digestFor(ROLL_1)).isEqualTo(Pseudonymiser.withSalt(TEST_SALT).digestFor(ROLL_1));
        assertThat(restored.saltFingerprint()).isEqualTo(result.saltFingerprint());
    }

    @Test
    @DisplayName("the key is written sealed, and opens to exactly what ingest produced")
    void theKeyIsSealedBeforeItIsWritten() throws Exception {
        CsvIngest.IngestResult result = ingest(WELL_FORMED);
        char[] passphrase = "a long instructor passphrase".toCharArray();

        byte[] sealed = result.sealedKeyFile(passphrase);

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain(ROLL_1, TA_1);
        assertThat(KeyFile.open(sealed, passphrase)).isEqualTo(result.keyFileContents());
    }

    @Test
    @DisplayName("a fresh salt gives unrelated digests for the same person")
    void freshSaltBreaksLinkageBetweenRuns() {
        Pseudonymiser first = Pseudonymiser.withSalt(TEST_SALT);
        Pseudonymiser second = Pseudonymiser.withRandomSalt();

        // Deliberate: without the key file, nobody can follow an assistant from one
        // offering's reports to the next. The instructor can, because he holds the key.
        assertThat(first.digestFor(TA_1)).isNotEqualTo(second.digestFor(TA_1));
        assertThat(first.saltFingerprint()).isNotEqualTo(second.saltFingerprint());
        // And the fingerprint recorded in a manifest must not be the salt itself.
        assertThat(first.saltFingerprint()).hasSize(12);
    }

    @Test
    @DisplayName("labels for one kind of participant are issued once, so they can never be renumbered")
    void labelsAreIssuedOnce() {
        Pseudonymiser p = Pseudonymiser.withSalt(TEST_SALT);
        p.assignLabels(List.of(ROLL_1), "Student ");

        assertThatThrownBy(() -> p.assignLabels(List.of(ROLL_2), "Student "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("renumber");
    }

    @Test
    @DisplayName("a bad row is reported with its line number and the rest of the file still loads")
    void malformedRowsDoNotAbortTheFile() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks
                CE23B092,Q1,Ananya Krishnan,7,10
                ME21B145,Q1,Ananya Krishnan,absent,10
                CE23B092,Q2,Rahul Venkatesh,14,10
                ,Q2,Rahul Venkatesh,5,10
                ME21B145,Q2,Rahul Venkatesh,8,10
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // A parser that stops at the first bad row makes the instructor fix his export one
        // error per attempt. All the problems are reported at once, each naming its line.
        assertThat(result.responses()).hasSize(2);
        assertThat(result.diagnostics()).anySatisfy(d ->
                assertThat(d.message()).contains("line 3").contains("could not read a number"));
        assertThat(result.diagnostics()).anySatisfy(d ->
                assertThat(d.message()).contains("line 4").contains("outside 0..10"));
        assertThat(result.diagnostics()).anySatisfy(d ->
                assertThat(d.message()).contains("line 5").contains("blank"));
    }

    @Test
    @DisplayName("a second mark on the same response is recorded as a link, not an error")
    void doubleMarkingIsTreatedAsTheAssetItIs() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks
                CE23B092,Q1,Ananya Krishnan,7,10
                CE23B092,Q1,Rahul Venkatesh,6,10
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // This row is the whole reason a question-wise course can be audited at all, so it
        // must not be discarded as a duplicate.
        assertThat(result.responses()).hasSize(2);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.diagnostics())
                .anySatisfy(d -> assertThat(d.code()).isEqualTo(Diagnostic.Code.EH008_DOUBLE_MARK_FOUND));
    }

    @Test
    @DisplayName("columns are found by name, in whatever order and spelling the export used")
    void columnsAreLocatedByHeaderName() throws IOException {
        String csv = """
                Marks,TA,Question,Roll No,Out Of
                7,Ananya Krishnan,Q1,CE23B092,10
                """;

        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.responses()).hasSize(1);
        assertThat(result.responses().get(0).score()).isEqualTo(7);
        assertThat(result.responses().get(0).maxScore()).isEqualTo(10);
    }

    @Test
    @DisplayName("a missing required column fails loudly, naming what it looked for")
    void missingRequiredColumnIsAnImmediateFailure() {
        String csv = """
                roll_no,question,marks
                CE23B092,Q1,7
                """;

        // Unlike a bad row, this is not recoverable and guessing would be worse than
        // failing: there is no assistant column, so there is no analysis to do.
        assertThatThrownBy(() -> ingest(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grader")
                .hasMessageContaining("assistant");
    }

    @Test
    @DisplayName("a file with no marking-order column says so, because it decides what can be tested")
    void absentPositionColumnIsReported() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks
                CE23B092,Q1,Ananya Krishnan,7,10
                """;

        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.diagnostics())
                .anySatisfy(d -> assertThat(d.message()).contains("order scripts were marked in"));
    }

    @Test
    @DisplayName("marks above 1 with no maximum column are refused, not scaled to a guess")
    void refusesToGuessTheMaximum() throws IOException {
        String csv = """
                roll_no,question,ta,marks
                CE23B092,Q1,Ananya Krishnan,7
                ME21B145,Q1,Ananya Krishnan,9
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // The tempting implementation takes the highest mark awarded as the maximum. It
        // is wrong in a way that never shows: nobody may have scored full marks, so the
        // guess is smallest on the hardest questions, which inflates their difficulty and
        // the severity of whoever marked them. Refusing is the only honest option, and
        // the message has to blame the missing column rather than the mark.
        assertThat(result.responses()).isEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(Diagnostic.Code.EH010_MAXIMUM_NOT_DECLARED);
            assertThat(d.message()).contains("highest seen is 9");
            assertThat(d.fixIt().orElseThrow()).contains("out_of");
        });
    }

    @Test
    @DisplayName("right-or-wrong marking needs no maximum column and is read as binary")
    void binaryMarkingNeedsNoMaximumColumn() throws IOException {
        String csv = """
                roll_no,question,ta,marks
                CE23B092,Q1,Ananya Krishnan,1
                ME21B145,Q1,Ananya Krishnan,0
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // Refusing here would be pedantry: with every mark 0 or 1 the maximum is not in
        // doubt, and a great many quizzes are exported exactly like this.
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.responses()).hasSize(2);
        assertThat(result.responses()).allSatisfy(r -> assertThat(r.maxScore()).isEqualTo(1));
    }

    @Test
    @DisplayName("a column of per-student totals is not silently taken as the question maximum")
    void inconsistentMaximumIsReported() throws IOException {
        String csv = """
                roll_no,question,ta,marks,out_of
                CE23B092,Q1,Ananya Krishnan,7,58
                ME21B145,Q1,Ananya Krishnan,9,61
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // This is the failure worth the most: a "total" column holding each student's
        // running total reads seven marks out of ten as seven out of fifty-eight, which
        // deflates that question and everyone who marked it, and produces a number that
        // looks entirely reasonable. A question's maximum cannot vary between two students
        // in the same assessment, so the variation itself is the alarm.
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(Diagnostic.Code.EH011_MAXIMUM_INCONSISTENT);
            assertThat(d.message()).contains("student's total");
        });
    }

    @Test
    @DisplayName("'total' is not treated as a maximum-score column at all")
    void totalIsNotAnAliasForTheMaximum() throws IOException {
        String csv = """
                roll_no,question,ta,marks,total
                CE23B092,Q1,Ananya Krishnan,7,58
                """;

        // With "total" no longer an alias, this file has no maximum column and marks above
        // 1, so it is refused rather than read with a fabricated denominator of 58.
        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.responses()).isEmpty();
        assertThat(result.diagnostics()).anySatisfy(d ->
                assertThat(d.code()).isEqualTo(Diagnostic.Code.EH010_MAXIMUM_NOT_DECLARED));
    }

    @Test
    @DisplayName("quoted fields containing commas are read as one field")
    void handlesQuotedFields() throws IOException {
        String csv = """
                roll_no,question,ta,marks,max_marks
                CE23B092,Q1,"Krishnan, Ananya",7,10
                """;

        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.responses()).hasSize(1);
        assertThat(result.design().graderCount()).isEqualTo(1);
        assertThat(result.keyFileContents()).contains("Krishnan, Ananya");
    }

    @Test
    @DisplayName("an ordinary mark sheet, one row per student, is read without reshaping it first")
    void readsOneRowPerStudent() throws IOException {
        String csv = """
                Roll No,Name,TA,Q1 (10),Q2 [10],Q3 /15,Total (35)
                CE23B092,Arjun,Ananya Krishnan,7,8.5,12,27.5
                ME21B145,Divya,Rahul Venkatesh,9,AB,,9
                """;

        CsvIngest.IngestResult result = ingest(csv);

        // Three questions for the first student; the second answered only Q1. Total is not a question.
        assertThat(result.responses()).hasSize(4);
        assertThat(result.design().questionCount()).isEqualTo(3);
        assertThat(result.design().graderCount()).isEqualTo(2);
        assertThat(result.responses()).extracting(r -> r.maxMarks()).contains(10.0, 15.0);
        assertThat(result.keyFileContents()).doesNotContain("Arjun", "Divya");
        assertThat(result.diagnostics()).noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    @Test
    @DisplayName("a mark sheet with no maximum in any header is refused, saying how to fix it")
    void aMarkSheetMustSayWhatEachQuestionIsOutOf() {
        String csv = """
                roll_no,ta,Q1,Q2
                CE23B092,Ananya Krishnan,7,8
                """;

        assertThatThrownBy(() -> ingest(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one row per student")
                .hasMessageContaining("Q1 (10)");
    }

    @Test
    @DisplayName("a bad cell in a mark sheet names the instructor's own row")
    void aMarkSheetKeepsItsLineNumbers() throws IOException {
        String csv = """
                roll_no,ta,Q1 (10),Q2 (10)
                CE23B092,Ananya Krishnan,7,8
                ME21B145,Rahul Venkatesh,12,8
                """;

        CsvIngest.IngestResult result = ingest(csv);

        assertThat(result.responses()).hasSize(3);
        assertThat(result.diagnostics()).anySatisfy(d -> assertThat(d.render()).contains("line 3"));
    }

    @Test
    @DisplayName("Excel's byte-order mark does not hide the first column")
    void excelByteOrderMarkIsIgnored() throws IOException {
        CsvIngest.IngestResult result = ingest("﻿" + WELL_FORMED);

        assertThat(result.responses()).hasSize(4);
    }
}
