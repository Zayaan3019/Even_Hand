package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.sim.DesignGenerator;

import java.util.List;

/**
 * Renders an audit verdict the way an instructor should read it, and prints the
 * scenario matrix when run directly.
 *
 * <p>Running {@code java ... AuditReport} is the fastest way to see the platform's
 * central claim demonstrated: seven marking arrangements, no marks anywhere, and a
 * different and correct answer for each.
 */
public final class AuditReport {

    private AuditReport() {
    }

    public static String render(String title, MarkingDesign design, AuditVerdict verdict) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append('\n');
        sb.append("  ").append(verdict.headline()).append('\n');
        sb.append("  verdict            : ").append(shortName(verdict)).append('\n');
        sb.append("  assistants         : ").append(design.graderCount()).append('\n');
        sb.append("  marks examined     : ").append(design.size()).append(" (no scores read)\n");
        sb.append("  excess indetermin. : ").append(verdict.certificate().excessNullity())
          .append(" beyond the 2 that anchoring removes\n");
        for (Diagnostic d : verdict.diagnostics()) {
            sb.append("  ").append(d.render().replace("\n", "\n  ")).append('\n');
        }
        return sb.toString();
    }

    /** The label used in the design document's scenario table. */
    public static String shortName(AuditVerdict verdict) {
        return switch (verdict) {
            case AuditVerdict.Estimable ignored -> "ESTIMABLE";
            case AuditVerdict.EstimableUnderExchangeability ignored -> "UNDER-EXCHANGEABILITY";
            case AuditVerdict.PartiallyEstimable p ->
                    "PARTIAL (" + p.estimableGraders().size() + " of "
                            + (p.estimableGraders().size() + p.blockedGraders().size()) + ")";
            case AuditVerdict.NotEstimable ignored -> "NOT-ESTIMABLE";
        };
    }

    private record Scenario(String label, MarkingDesign design) {}

    public static void main(String[] args) {
        List<Scenario> scenarios = List.of(
                new Scenario("Question-wise + double-marking",
                        DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8)),
                new Scenario("Question-wise, marker rotated",
                        DesignGenerator.rotatingCrossed(60, 5, 5, 5)),
                new Scenario("Script-wise, random piles",
                        DesignGenerator.scriptWiseRandom(60, 5, 4, 42L)),
                new Scenario("Script-wise, roll-number blocks",
                        DesignGenerator.scriptWiseFixedBlocks(60, 5, 4)),
                new Scenario("Question-wise, no overlap",
                        DesignGenerator.questionWise(60, 5, 5)),
                new Scenario("Mixed across assessments",
                        DesignGenerator.mixed(60, 6, 4, 7L)),
                new Scenario("Single marker",
                        DesignGenerator.singleMarker(60, 5)),
                new Scenario("Two disconnected islands",
                        DesignGenerator.disconnectedIslands(30, 3, 2)),
                new Scenario("Islands + one bridging mark",
                        DesignGenerator.disconnectedIslandsWithOneBridge(30, 3, 2)));

        System.out.printf("%-34s %3s %3s  %-22s%n", "ARRANGEMENT", "J", "EXC", "VERDICT");
        System.out.println("-".repeat(70));
        for (Scenario s : scenarios) {
            AuditVerdict v = IdentifiabilityAudit.audit(s.design());
            System.out.printf("%-34s %3d %3d  %-22s%n",
                    s.label(), s.design().graderCount(), v.certificate().excessNullity(), shortName(v));
        }
        System.out.println();
        MarkingDesign headline = DesignGenerator.questionWise(60, 5, 5);
        System.out.println(render("A refusal, in full:", headline, IdentifiabilityAudit.audit(headline)));
    }
}
