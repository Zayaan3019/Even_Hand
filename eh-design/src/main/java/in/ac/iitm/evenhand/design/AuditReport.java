package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.sim.DesignGenerator;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders an audit verdict the way an instructor should read it, and prints the
 * scenario matrix when run directly.
 *
 * <p>Running {@code java ... AuditReport} is the fastest way to see the platform's
 * central claims demonstrated: a dozen marking arrangements, no marks anywhere, and for
 * each one a correct answer to three questions &mdash; can severity be measured, could
 * it have moved anyone, and can drift be measured.
 */
public final class AuditReport {

    private AuditReport() {
    }

    public static String render(String title, MarkingDesign design, AuditVerdict verdict) {
        DriftAudit.Result drift = DriftAudit.audit(design);
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        sb.append("  ").append(verdict.headline()).append('\n');
        sb.append("  ").append(verdict.exposure().headline()).append('\n');
        sb.append("  severity           : ").append(shortName(verdict)).append('\n');
        sb.append("  exposure           : ").append(verdict.exposure().shortName()).append('\n');
        sb.append("  drift              : ").append(drift.status().shortName()).append('\n');
        sb.append("  assistants         : ").append(design.graderCount()).append('\n');
        sb.append("  marks examined     : ").append(design.size()).append(" (no scores read)\n");
        sb.append("  excess indetermin. : ").append(verdict.certificate().excessNullity())
          .append(" beyond the 2 that anchoring removes\n");
        for (Diagnostic d : verdict.diagnostics()) {
            sb.append("  ").append(d.render().replace("\n", "\n  ")).append('\n');
        }
        drift.diagnostic().ifPresent(d -> sb.append("  ").append(d.render().replace("\n", "\n  ")).append('\n'));
        return sb.toString();
    }

    /** The label used in the design document's scenario table. */
    public static String shortName(AuditVerdict verdict) {
        return switch (verdict) {
            case AuditVerdict.Estimable ignored -> "ESTIMABLE";
            case AuditVerdict.EstimableUnderExchangeability ignored -> "UNDER-EXCH.";
            case AuditVerdict.PartiallyEstimable p -> "PARTIAL ("
                    + p.groups().stream().map(g -> String.valueOf(g.size())).collect(Collectors.joining("+"))
                    + ")";
            case AuditVerdict.NotEstimable ignored -> "NOT-ESTIMABLE";
        };
    }

    /**
     * One row of the scenario matrix: an arrangement whose right answer was worked out
     * on paper, with the weight each question carries in the grade.
     */
    public record Scenario(String label, MarkingDesign design, double[] questionWeights) {

        public Scenario(String label, MarkingDesign design) {
            this(label, design, ExposureAudit.unitWeights(design));
        }

        public AuditVerdict audit() {
            return IdentifiabilityAudit.audit(design, questionWeights);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The scenario matrix. The design document's Appendix A is this list's output, and
     * the tests run their cross-checks over exactly this list, so the two cannot drift.
     */
    public static List<Scenario> scenarios() {
        MarkingDesign rotated = DesignGenerator.rotatingCrossed(60, 5, 5, 5);
        return List.of(
                new Scenario("Question-wise + double-marking",
                        DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8)),
                new Scenario("Question-wise, marker rotated", rotated),
                new Scenario("  same, maxima 5/10/15/10/10", rotated, new double[] {5, 10, 15, 10, 10}),
                new Scenario("Script-wise, random piles",
                        DesignGenerator.scriptWiseRandom(60, 5, 4, 42L)),
                new Scenario("Script-wise, roll-number blocks",
                        DesignGenerator.scriptWiseFixedBlocks(60, 5, 4)),
                new Scenario("Question-wise, no overlap",
                        DesignGenerator.questionWise(60, 5, 5)),
                new Scenario("Question-wise, shared stack order",
                        DesignGenerator.questionWiseWithStackOrder(60, 5, 5, false)),
                new Scenario("Question-wise, alternate stacks reversed",
                        DesignGenerator.questionWiseWithStackOrder(60, 5, 5, true)),
                new Scenario("Mixed across assessments",
                        DesignGenerator.mixed(60, 6, 4, 7L)),
                new Scenario("Single marker",
                        DesignGenerator.singleMarker(60, 5)),
                new Scenario("Two disconnected islands",
                        DesignGenerator.disconnectedIslands(30, 3, 2)),
                new Scenario("Islands + one bridging mark",
                        DesignGenerator.disconnectedIslandsWithOneBridge(30, 3, 2)));
    }

    public static void main(String[] args) {
        String format = "%-40s %3s %3s  %-15s %-14s %-13s%n";
        System.out.printf(format, "ARRANGEMENT", "J", "EXC", "SEVERITY", "EXPOSURE", "DRIFT");
        System.out.println("-".repeat(92));
        for (Scenario s : scenarios()) {
            AuditVerdict v = s.audit();
            System.out.printf(format, s.label(), s.design().graderCount(), v.certificate().excessNullity(),
                    shortName(v), v.exposure().shortName(), DriftAudit.audit(s.design()).status().shortName());
        }
        System.out.println();
        MarkingDesign headline = DesignGenerator.questionWise(60, 5, 5);
        System.out.println(render("Question-wise, in full:", headline, IdentifiabilityAudit.audit(headline)));
    }
}
