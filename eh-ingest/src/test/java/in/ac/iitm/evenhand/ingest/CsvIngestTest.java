package in.ac.iitm.evenhand.ingest;

import in.ac.iitm.evenhand.core.Diagnostic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                result.diagnostics().toString(),
                result.saltFingerprint());

        for (String identifier : List.of(ROLL_1, ROLL_2, TA_1, TA_2)) {
            assertThat(everythingDownstream)
                    .as("identifier '%s' must not survive ingest", identifier)
                    .doesNotContain(identifier);
        }

        // The one place they legitimately appear is the key file, which is handed to the
        // instructor and which the application never reads back.
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
    @DisplayName("a fresh salt gives unrelated digests for the same person")
    void freshSaltBreaksLinkageBetweenRuns() {
        Pseudonymiser first = Pseudonymiser.withSalt(TEST_SALT);
        Pseudonymiser second = Pseudonymiser.withRandomSalt();

        // Deliberate: without the key file, nobody can follow an assistant from one
        // report to the next. The instructor can, because he holds the key.
        assertThat(first.digestFor(TA_1)).isNotEqualTo(second.digestFor(TA_1));
        assertThat(first.saltFingerprint()).isNotEqualTo(second.saltFingerprint());
        // And the fingerprint recorded in a manifest must not be the salt itself.
        assertThat(first.saltFingerprint()).hasSize(12);
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
}
