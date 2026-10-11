package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.sim.DesignGenerator;

public class ScaleProbe {
    static void time(String label, MarkingDesign d) {
        long t0 = System.nanoTime();
        var v = IdentifiabilityAudit.audit(d);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("%-46s cells=%-6d cols=%-5d %6d ms  %s%n",
                label, d.size(), d.studentCount()+d.questionCount()+d.graderCount(), ms,
                AuditReport.shortName(v));
    }
    public static void main(String[] a) {
        time("qw+dbl  60 stu,  5 q, 5 TA", DesignGenerator.questionWiseWithDoubleMarking(60, 5, 5, 8));
        time("qw+dbl 150 stu, 10 q, 5 TA", DesignGenerator.questionWiseWithDoubleMarking(150, 10, 5, 20));
        time("qw+dbl 300 stu, 12 q, 8 TA", DesignGenerator.questionWiseWithDoubleMarking(300, 12, 8, 30));
        time("script 150 stu, 10 q, 5 TA", DesignGenerator.scriptWiseRandom(150, 10, 5, 1L));
        time("cross  150 stu, 10 q, 5 TA", DesignGenerator.rotatingCrossed(150, 10, 5, 5));
        time("cross  400 stu, 15 q,10 TA", DesignGenerator.rotatingCrossed(400, 15, 10, 10));
    }
}
