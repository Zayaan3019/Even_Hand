package in.ac.iitm.evenhand.ingest;

import in.ac.iitm.evenhand.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading what students wrote, in every layout an instructor or an exam platform exports. */
class AnswerIngestTest {

    private static final byte[] SALT = "even-hand-test-salt-not-for-real-use".getBytes(StandardCharsets.UTF_8);

    private static CsvIngest.IngestResult ingest(String csv) throws IOException {
        return CsvIngest.read(new BufferedReader(new StringReader(csv)), Pseudonymiser.withSalt(SALT));
    }

    /** question label + marks -> answer, so assertions do not depend on pseudonym order. */
    private static Map<String, String> answersByQuestionAndMark(CsvIngest.IngestResult r) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < r.responses().size(); i++) {
            Response x = r.responses().get(i);
            out.put(r.design().questionLabels().get(x.question()) + "@" + x.marks(), r.answers().get(i));
        }
        return out;
    }

    @Test
    @DisplayName("one row per answer: an answer column is read, and a quoted answer may span lines")
    void longFormatWithMultilineAnswers() throws IOException {
        String csv = "roll_no,question,ta,marks,max_marks,answer\r\n"
                + "CE23B092,Q1,Ravi,7,10,\"The moment is maximum\r\nwhere shear changes sign, i.e. \"\"V = 0\"\".\"\r\n"
                + "ME21B145,Q1,Meena,4,10,Shear is zero at the support.\r\n"
                + "ME21B146,Q1,Meena,5,10,\r\n";
        CsvIngest.IngestResult r = ingest(csv);
        assertThat(r.responses()).hasSize(3);
        Map<String, String> a = answersByQuestionAndMark(r);
        assertThat(a.get("Q1@7.0")).isEqualTo("The moment is maximum\nwhere shear changes sign, i.e. \"V = 0\".");
        assertThat(a.get("Q1@4.0")).isEqualTo("Shear is zero at the support.");
        assertThat(a.get("Q1@5.0")).isEmpty();
        assertThat(r.answerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("a line number in a diagnostic is the line the bad row starts on, even after a multi-line answer")
    void lineNumbersSurviveMultilineAnswers() throws IOException {
        String csv = "roll_no,question,ta,marks,max_marks,answer\n"
                + "CE23B092,Q1,Ravi,7,10,\"line one\nline two\nline three\"\n"
                + "ME21B145,Q1,Meena,14,10,too many marks\n";
        CsvIngest.IngestResult r = ingest(csv);
        assertThat(r.diagnostics()).anyMatch(d -> d.message().startsWith("line 5:"));
    }

    @Test
    @DisplayName("an unclosed quote is reported, not read as one enormous answer")
    void unclosedQuote() {
        assertThatThrownBy(() -> ingest("roll_no,question,ta,marks,max_marks,answer\nCE23B092,Q1,Ravi,7,10,\"never closed\n"))
                .hasMessageContaining("never closed");
    }

    @Test
    @DisplayName("one row per student: 'Q1 answer', 'Answer Q2' and 'Q3 (response)' pair with their questions")
    void wideSheetAnswerColumns() throws IOException {
        String csv = "roll_no,name,ta,Q1 (10),Q1 answer,Q2 (10),Answer Q2,Q3 (5),Q3 (response),Total\n"
                + "CE23B092,Asha,Ravi,7,First answer,8,Second answer,3,Third answer,18\n"
                + "ME21B145,Babu,Meena,6,Another first,AB,,2,,8\n";
        CsvIngest.IngestResult r = ingest(csv);
        Map<String, String> a = answersByQuestionAndMark(r);
        assertThat(a).containsEntry("Q1@7.0", "First answer").containsEntry("Q2@8.0", "Second answer")
                .containsEntry("Q3@3.0", "Third answer").containsEntry("Q1@6.0", "Another first")
                .containsEntry("Q3@2.0", "");
        assertThat(r.responses()).hasSize(5);
        assertThat(r.diagnostics()).anyMatch(d -> d.message().contains("Written answers were found for 3"));
    }

    @Test
    @DisplayName("an LMS export: 'Response 1' pairs with 'Q. 1 /10.00', and 'Grade/20.00' is not a question")
    void moodleResponses() throws IOException {
        String csv = "Surname,First name,Email address,State,Grade/20.00,Q. 1 /10.00,Response 1,Right answer 1,"
                + "Q. 2 /10.00,Response 2,Graded by\n"
                + "Student,001,ce24b012@smail.iitm.ac.in,Finished,13.00,6.50,Tension in BC,Compression,6.50,Linear elastic,Arjun\n"
                + "Student,002,ce24b015@smail.iitm.ac.in,Finished,17.00,9.00,Compression in BC,Compression,8.00,Small deflections,Ravi\n"
                + ",,,Overall average,15.00,7.75,,,7.25,,\n";
        CsvIngest.IngestResult r = ingest(csv);
        assertThat(r.design().questionLabels()).containsExactly("Q. 1", "Q. 2");
        assertThat(r.responses()).hasSize(4);
        Map<String, String> a = answersByQuestionAndMark(r);
        assertThat(a).containsEntry("Q. 1@6.5", "Tension in BC").containsEntry("Q. 2@8.0", "Small deflections");
        assertThat(labelsOf(r)).doesNotContain("Right answer 1");
    }

    @Test
    @DisplayName("a Google Forms quiz: Score '23.5 / 40' is the mark, and every answer column is joined into its text")
    void googleForms() throws IOException {
        String csv = "Timestamp,Email address,Score,Roll number,Q1. Explain briefly,Q2. Show the key step,TA\n"
                + "2026/10/10 11:39:23 AM GMT+5:30,ae24b005@smail.iitm.ac.in,11 / 40,AE24B005,"
                + "\"Double integration of M/EI.\",\"Method of sections, BC tensile.\",Ravi\n"
                + "2026/10/10 10:55:43 AM GMT+5:30,ae24b008@smail.iitm.ac.in,23.5 / 40,AE24B008,"
                + "Max moment at zero shear.,,Arjun\n";
        CsvIngest.IngestResult r = ingest(csv);
        assertThat(r.responses()).hasSize(2);
        Map<String, String> a = answersByQuestionAndMark(r);
        assertThat(a.get("Score@11.0")).isEqualTo("Double integration of M/EI.\nMethod of sections, BC tensile.");
        assertThat(a.get("Score@23.5")).isEqualTo("Max moment at zero shear.");
        assertThat(r.responses().get(0).maxMarks()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("roll numbers, e-mails and TA names written inside an answer never get past ingest")
    void identifiersAreScrubbed() throws IOException {
        String csv = "roll_no,question,ta,marks,max_marks,answer\n"
                + "CE23B092,Q1,Ravi Kumar,7,10,\"CE23B092 here. As ravi kumar said, use M/EI. Mail me: x.y@gmail.com\"\n"
                + "ME21B145,Q1,Meena,4,10,My friend ae24b001 helped; Meena ma'am agreed\n";
        CsvIngest.IngestResult r = ingest(csv);
        String all = String.join(" | ", r.answers());
        assertThat(all).doesNotContainIgnoringCase("CE23B092").doesNotContainIgnoringCase("ravi kumar")
                .doesNotContain("x.y@gmail.com").doesNotContainIgnoringCase("ae24b001").doesNotContain("Meena");
        assertThat(all).contains("[roll number] here").contains("[name] said").contains("[email]");
        assertThat(r.diagnostics()).anyMatch(d -> d.message().contains("replaced by placeholders"));
    }

    @Test
    @DisplayName("a KEY row is the instructor's answer key: kept as each question's reference, never a student")
    void answerKeyRow() throws IOException {
        String csv = "roll_no,ta,Q1 (10),Q1 answer,Q2 (5),Q2 answer\n"
                + "KEY,,,\"Moment is maximum where shear is zero.\",,\"Tension, 25 kN.\"\n"
                + "CE23B092,Ravi,7,Max moment at zero shear.,3,Tension\n"
                + "ME21B145,Meena,4,At the support.,2,Compression\n";
        CsvIngest.IngestResult r = ingest(csv);
        assertThat(r.design().studentCount()).isEqualTo(2);
        assertThat(r.responses()).hasSize(4);
        assertThat(r.references()).hasSize(2);
        int q1 = r.design().questionLabels().indexOf("Q1");
        assertThat(r.references().get(q1)).isEqualTo("Moment is maximum where shear is zero.");
        assertThat(r.diagnostics()).anyMatch(d -> d.message().contains("answer key was found for 2"));

        String longForm = "roll_no,question,ta,marks,max_marks,answer\n"
                + "Answer key,Q1,,10,10,The reference answer.\n"
                + "CE23B092,Q1,Ravi,7,10,A student answer.\n";
        CsvIngest.IngestResult l = ingest(longForm);
        assertThat(l.design().studentCount()).isEqualTo(1);
        assertThat(l.references()).containsValue("The reference answer.");
    }

    @Test
    @DisplayName("files without answers read exactly as before: one empty answer per response")
    void noAnswers() throws IOException {
        CsvIngest.IngestResult r = ingest("roll_no,question,ta,marks,max_marks\nCE23B092,Q1,Ravi,7,10\n");
        assertThat(r.answers()).containsExactly("");
        assertThat(r.answerCount()).isZero();
    }

    private static List<String> labelsOf(CsvIngest.IngestResult r) {
        return r.design().questionLabels();
    }
}
