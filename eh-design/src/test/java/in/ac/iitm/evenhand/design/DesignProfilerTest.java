package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.Arrangement;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Provenance;
import in.ac.iitm.evenhand.core.Response;
import in.ac.iitm.evenhand.core.ScoreType;
import in.ac.iitm.evenhand.design.sim.DesignGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generalisation claim, tested at the point where it is easiest to fake.
 *
 * <p>It is no achievement to classify an arrangement the caller already named. The
 * generator never labels what it produces &mdash; it returns a {@link MarkingDesign},
 * which carries only who marked what &mdash; so every expectation below is the profiler
 * recovering the shape of a course from the marking alone.
 */
class DesignProfilerTest {

    @Test
    @DisplayName("script-wise marking is recognised from coverage, not from being told")
    void recognisesScriptWise() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.scriptWiseRandom(60, 5, 4, 42L));

        assertThat(report.arrangement().value()).isEqualTo(Arrangement.SCRIPT_WISE);
        assertThat(report.arrangement().provenance()).isEqualTo(Provenance.DETECTED);
        // The evidence is what the instructor reads, so it has to be legible rather than
        // a pair of ratios.
        assertThat(report.arrangement().evidence().orElseThrow())
                .contains("100% of the questions")
                .contains("of the class");
    }

    @Test
    @DisplayName("question-wise marking is recognised")
    void recognisesQuestionWise() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.questionWise(60, 5, 5));

        assertThat(report.arrangement().value()).isEqualTo(Arrangement.QUESTION_WISE);
        assertThat(report.notes())
                .anySatisfy(n -> assertThat(n).contains("difficulty of the question"));
    }

    @Test
    @DisplayName("a few second-marked scripts do not make a question-wise course look script-wise")
    void doubleMarkingDoesNotDisguiseQuestionWiseMarking() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8));

        // This is the case that defeats the obvious implementation. Counting questions per
        // assistant sees two rather than one and reads the course as script-wise; coverage
        // sees two questions out of five and gets it right. It matters because this is the
        // shape of the course the project was built for.
        assertThat(report.arrangement().value()).isEqualTo(Arrangement.QUESTION_WISE);
        assertThat(report.doubleMarkedUnits()).isEqualTo(40);
        assertThat(report.notes())
                .anySatisfy(n -> assertThat(n).contains("marked by more than one assistant"));
    }

    @Test
    @DisplayName("rotation is recognised as its own arrangement, not lumped in with the rest")
    void recognisesRotation() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.rotatingCrossed(60, 5, 5, 5));

        // Worth distinguishing because it is the only arrangement that identifies every
        // severity at no extra marking cost, and so is the first repair to recommend.
        assertThat(report.arrangement().value()).isEqualTo(Arrangement.CROSSED);
        assertThat(report.doubleMarkedUnits()).isZero();
        assertThat(report.notes()).anySatisfy(n -> assertThat(n).contains("without anyone marking twice"));
    }

    @Test
    @DisplayName("one marker is recognised without needing any coverage arithmetic")
    void recognisesSingleMarker() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.singleMarker(60, 5));

        assertThat(report.arrangement().value()).isEqualTo(Arrangement.SINGLE_MARKER);
    }

    @Test
    @DisplayName("an arrangement that is neither is reported as mixed rather than forced into a box")
    void reportsMixedRatherThanGuessing() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.mixed(60, 6, 4, 7L));

        assertThat(report.arrangement().value())
                .isIn(Arrangement.MIXED, Arrangement.SCRIPT_WISE, Arrangement.QUESTION_WISE);
        assertThat(report.arrangement().provenance()).isEqualTo(Provenance.DETECTED);
    }

    @Test
    @DisplayName("with no marks at all, the arrangement is still detected and the score type is not guessed")
    void profilesFromTheArrangementAloneWithoutMarks() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.questionWise(100, 4, 4));

        // The mode that matters in week one: the instructor learns what kind of
        // arrangement he has before he shares a single score.
        assertThat(report.arrangement().provenance()).isEqualTo(Provenance.DETECTED);
        assertThat(report.arrangement().value()).isEqualTo(Arrangement.QUESTION_WISE);

        // And the profiler declines to invent what it cannot see.
        assertThat(report.scoreType().provenance()).isEqualTo(Provenance.DEFAULTED);
        assertThat(report.notes())
                .anySatisfy(n -> assertThat(n).contains("cannot be determined without the marks"));
    }

    @Test
    @DisplayName("binary and partial-credit scoring are told apart from the marks")
    void detectsScoreType() {
        MarkingDesign design = DesignGenerator.questionWise(4, 2, 2);

        List<Response> binary = new ArrayList<>();
        List<Response> partial = new ArrayList<>();
        int i = 0;
        for (DesignCell cell : design.cells()) {
            binary.add(new Response(cell, i % 2, 1));
            partial.add(new Response(cell, i % 8, 10));
            i++;
        }

        assertThat(DesignProfiler.profile(design, binary).scoreType().value())
                .isEqualTo(ScoreType.BINARY);
        assertThat(DesignProfiler.profile(design, partial).scoreType().value())
                .isEqualTo(ScoreType.PARTIAL_CREDIT);
    }

    @Test
    @DisplayName("ordered categories are not claimed to be distinguishable from part-marks")
    void doesNotPretendToTellOrdinalFromPartialCredit() {
        MarkingDesign design = DesignGenerator.questionWise(4, 2, 2);
        List<Response> responses = new ArrayList<>();
        int i = 0;
        for (DesignCell cell : design.cells()) {
            responses.add(new Response(cell, i++ % 4, 3));
        }

        DesignProfiler.ProfileReport report = DesignProfiler.profile(design, responses);

        // Both are ordered integers in a score column and nothing in the data separates
        // them; only the instructor knows whether the steps are equal in marks. Saying so
        // is better than picking one and being quietly wrong about how the thresholds are
        // modelled.
        assertThat(report.scoreType().value()).isEqualTo(ScoreType.PARTIAL_CREDIT);
        assertThat(report.notes())
                .anySatisfy(n -> assertThat(n).contains("ordered categories rather than marks"));
    }

    @Test
    @DisplayName("an absent marking-order column is reported, because it decides what can be tested")
    void reportsWhetherDriftCanBeTestedAtAll() {
        DesignProfiler.ProfileReport withoutOrder =
                DesignProfiler.profile(DesignGenerator.questionWise(20, 3, 3));
        assertThat(withoutOrder.positionRecorded().value()).isFalse();
        assertThat(withoutOrder.notes()).anySatisfy(n -> assertThat(n).contains("drift"));

        MarkingDesign withOrder = MarkingDesign.of(
                List.of(new DesignCell(0, 0, 0, 0, 1), new DesignCell(1, 0, 1, 0, 2)),
                List.of("Student 1", "Student 2"), List.of("Q1"), List.of("Assistant 1", "Assistant 2"));
        assertThat(DesignProfiler.profile(withOrder).positionRecorded().value()).isTrue();
    }

    @Test
    @DisplayName("an instructor override replaces a detected value and is recorded as declared")
    void instructorOverrideWins() {
        DesignProfiler.ProfileReport report =
                DesignProfiler.profile(DesignGenerator.scriptWiseRandom(40, 4, 4, 1L));

        var overridden = report.arrangement()
                .overriddenWith(Arrangement.MIXED, "Prof. Kunhi Mohamed");

        // The instructor knows things the data does not record - that one assistant took
        // over another's pile halfway, say. An override must win, and must be visible in
        // the report footer as an assertion rather than a measurement.
        assertThat(report.arrangement().value()).isEqualTo(Arrangement.SCRIPT_WISE);
        assertThat(overridden.value()).isEqualTo(Arrangement.MIXED);
        assertThat(overridden.provenance()).isEqualTo(Provenance.DECLARED);
        assertThat(overridden.describe("marking arrangement"))
                .contains("declared by Prof. Kunhi Mohamed");
    }
}
