package in.ac.iitm.evenhand.report;

import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.Anchoring;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;
import in.ac.iitm.evenhand.design.IdentifiabilityAudit;
import in.ac.iitm.evenhand.estimate.SeverityEstimator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.outcome.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReportTest {

    /** 30 students, 4 questions, each marked by two of three assistants; assistant 0 is harsh. */
    private static ReportInput estimatedCourse() {
        return estimatedCourse(9);
    }

    private static ReportInput estimatedCourse(int nullSimulations) {
        List<Response> rs = new ArrayList<>();
        List<String> students = new ArrayList<>();
        for (int s = 0; s < 30; s++) {
            students.add("Student " + (s + 1));
            for (int q = 0; q < 4; q++) {
                int first = (q + s) % 3;
                for (int g : new int[] { first, (first + 1) % 3 }) {
                    int score = Math.max(0, Math.min(10, 4 + (s % 7) - (g == 0 ? 2 : 0)));
                    rs.add(new Response(new DesignCell(s, q, g, 0, DesignCell.NO_POSITION, g == first), score, 10));
                }
            }
        }
        MarkingDesign design = MarkingDesign.fromResponses(rs, students, List.of("Q1", "Q2", "Q3", "Q4"),
                List.of("Assistant 1", "Assistant 2", "Assistant 3"));
        AuditVerdict verdict = IdentifiabilityAudit.audit(design);
        FittedModel model = SeverityEstimator.fit(AnchoredDesign.of(design, verdict, Anchoring.standard()), rs,
                Settings.defaults().withDraws(20, nullSimulations));
        Outcome.Result outcome = Outcome.assess(model, rs, design, new double[] { 1 },
                CutoffPolicy.iitmConventionalRelative(), Outcome.Gate.standard());
        return new ReportInput("CE3020 <test>", design, verdict, Optional.of(model), Optional.of(outcome),
                List.of(), List.of("cut-off rule: SIGMA_BANDS (declared)"));
    }

    @Test
    @DisplayName("the Class Committee sheet names no assistant and no student")
    void committeeSheetIsAggregateOnly() {
        String html = HtmlReport.committee(estimatedCourse());

        assertThat(html).doesNotContain("Assistant ");
        assertThat(html).doesNotContain("Student ");
        assertThat(html).contains("R.24.1");
    }

    @Test
    @DisplayName("the instructor report shows every assistant with an interval, in marks")
    void instructorReportShowsSeverities() {
        String html = HtmlReport.instructor(estimatedCourse());

        assertThat(html).contains("Assistant 1", "Assistant 2", "Assistant 3", "95% interval", "On a 10-mark question");
        assertThat(html).contains("No-effect test");
    }

    @Test
    @DisplayName("whatever the course is called, it cannot inject markup into the report")
    void escapesInput() {
        String html = HtmlReport.instructor(estimatedCourse());

        assertThat(html).contains("CE3020 &lt;test&gt;");
        assertThat(html).doesNotContain("CE3020 <test>");
    }

    @Test
    @DisplayName("a refused design says why, and shows no severity at all")
    void refusedDesignShowsNoNumbers() {
        List<Response> rs = new ArrayList<>();
        List<String> students = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            students.add("Student " + (s + 1));
            for (int q = 0; q < 2; q++) rs.add(new Response(new DesignCell(s, q, q), 5, 10));
        }
        MarkingDesign design = MarkingDesign.fromResponses(rs, students, List.of("Q1", "Q2"),
                List.of("Assistant 1", "Assistant 2"));
        AuditVerdict verdict = IdentifiabilityAudit.audit(design);
        ReportInput in = new ReportInput("Elective", design, verdict, Optional.empty(), Optional.empty(),
                verdict.diagnostics(), List.of());

        String html = HtmlReport.instructor(in);

        assertThat(verdict.permitsEstimation()).isFalse();
        assertThat(html).contains("Severity was not estimated", "Bottom line", "cannot tell a harsh TA");
        assertThat(html).doesNotContain("95% interval");
    }

    @Test
    @DisplayName("the report opens with its conclusion and the one thing to do next")
    void bottomLineNamesTheHarshestAssistant() {
        String html = HtmlReport.instructor(estimatedCourse(39));

        assertThat(html).contains("Bottom line", "did not mark alike", "Assistant 1 was the harshest");
        assertThat(html.indexOf("Bottom line")).isLessThan(html.indexOf("1. Can this marking"));
    }

    @Test
    @DisplayName("when chance explains the differences, the report says there is nothing to re-check")
    void bottomLineWhenTheAssistantsAgree() {
        // Nine null simulations cannot reach p < 0.05, so the test cannot reject.
        String html = HtmlReport.instructor(estimatedCourse(9));

        assertThat(html).contains("marked consistently", "No script needs a second look");
    }
}
