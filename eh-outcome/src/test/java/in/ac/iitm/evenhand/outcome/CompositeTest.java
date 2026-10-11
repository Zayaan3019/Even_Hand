package in.ac.iitm.evenhand.outcome;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompositeTest {

    // Two students. Quiz (assessment 0): Q0 out of 10. End-sem (assessment 1): Q1 out of 20.
    // Assistant 0 marked the quiz, assistant 1 the end-sem.
    private static Response mark(int student, int question, int grader, int assessment, int score, int max, boolean record) {
        return new Response(new DesignCell(student, question, grader, assessment, DesignCell.NO_POSITION, record), score, max);
    }

    private static MarkingDesign design(List<Response> rs) {
        return MarkingDesign.fromResponses(rs, List.of("S1", "S2"), List.of("Q0", "Q1"), List.of("A0", "A1"));
    }

    @Test
    @DisplayName("weighted by the course's weights, out of 100")
    void weighted() {
        List<Response> rs = List.of(
                mark(0, 0, 0, 0, 5, 10, true), mark(0, 1, 1, 1, 20, 20, true),
                mark(1, 0, 0, 0, 10, 10, true), mark(1, 1, 1, 1, 10, 20, true));

        double[] totals = new Composite(rs, design(rs), new double[] { 25, 75 }).asMarked();

        assertThat(totals[0]).isCloseTo(0.25 * 50 + 0.75 * 100, within(1e-9));
        assertThat(totals[1]).isCloseTo(0.25 * 100 + 0.75 * 50, within(1e-9));
    }

    @Test
    @DisplayName("a second, checking mark never counts; two marks of record are averaged")
    void marksOfRecord() {
        List<Response> rs = List.of(
                mark(0, 0, 0, 0, 4, 10, true), mark(0, 0, 1, 0, 10, 10, false),   // check mark ignored
                mark(1, 0, 0, 0, 4, 10, true), mark(1, 0, 1, 0, 8, 10, true),     // both count: 6
                mark(0, 1, 1, 1, 0, 20, true), mark(1, 1, 1, 1, 0, 20, true));

        double[] totals = new Composite(rs, design(rs), new double[] { 1, 0 }).asMarked();

        assertThat(totals[0]).isCloseTo(40, within(1e-9));
        assertThat(totals[1]).isCloseTo(60, within(1e-9));
    }

    @Test
    @DisplayName("an absentee scores zero in the assessment they missed")
    void absentee() {
        List<Response> rs = List.of(
                mark(0, 0, 0, 0, 10, 10, true), mark(0, 1, 1, 1, 20, 20, true),
                mark(1, 1, 1, 1, 20, 20, true));

        double[] totals = new Composite(rs, design(rs), new double[] { 50, 50 }).asMarked();

        assertThat(totals[1]).isCloseTo(50, within(1e-9));
    }

    @Test
    @DisplayName("correction gives back each assistant's severity, never beyond full marks or below zero")
    void correctionIsClamped() {
        List<Response> rs = List.of(
                mark(0, 0, 0, 0, 9, 10, true), mark(0, 1, 1, 1, 1, 20, true),
                mark(1, 0, 0, 0, 5, 10, true), mark(1, 1, 1, 1, 10, 20, true));
        Composite c = new Composite(rs, design(rs), new double[] { 50, 50 });

        // Assistant 0 harsh by 20 points (2 marks on a 10-mark question); assistant 1 lenient by 10 points.
        double[] corrected = c.corrected(new double[] { 20, -10 });

        // Student 1: quiz 9+2 = 11 is clamped to 10; end-sem 1-2 = -1 is clamped to 0.
        assertThat(corrected[0]).isCloseTo(50 * 10 / 10.0 + 50 * 0 / 20.0, within(1e-9));
        // Student 2: quiz 5+2 = 7; end-sem 10-2 = 8.
        assertThat(corrected[1]).isCloseTo(50 * 7 / 10.0 + 50 * 8 / 20.0, within(1e-9));
    }
}
