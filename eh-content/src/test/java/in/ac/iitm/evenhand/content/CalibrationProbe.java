package in.ac.iitm.evenhand.content;

import java.util.Locale;

/**
 * Not a test: a calibration probe run by hand while tuning. Prints the false-alarm rate of the
 * bootstrap and placebo tests on identical assistants, and interval coverage and error when
 * the assistants differ, over many synthetic courses.
 *
 * <pre>java -cp ... in.ac.iitm.evenhand.content.CalibrationProbe 100</pre>
 */
public final class CalibrationProbe {

    public static void main(String[] args) {
        int runs = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        ContentSettings s = ContentSettings.defaults().withDraws(200);
        if (args.length > 1) s = s.withShrinkage(Double.parseDouble(args[1]));
        if (args.length > 3) s = s.withMatching(Integer.parseInt(args[2]), Double.parseDouble(args[3]), s.lexicalCaliper());
        for (SyntheticCourse.Allocation alloc : new SyntheticCourse.Allocation[] {
                SyntheticCourse.Allocation.RANDOM, SyntheticCourse.Allocation.BY_ABILITY}) {
            int bootAlarms = 0, placeboAlarms = 0, covered = 0, total = 0, detected = 0;
            double sse = 0;
            double[] nullMean = new double[4];
            for (int run = 0; run < runs; run++) {
                SyntheticCourse nul = new SyntheticCourse(200, 4, new double[] {0, 0, 0, 0}, alloc, 0, 0, null, 1000 + run);
                ContentResult r0 = ContentAnalysis.analyse(nul.input(), s);
                if (r0.pValue() < 0.05) bootAlarms++;
                for (int j = 0; j < 4; j++) nullMean[j] += r0.severity()[j] / runs;
                if (r0.causal().orElseThrow().placebo().pValue() < 0.05) placeboAlarms++;

                SyntheticCourse c = new SyntheticCourse(200, 4, new double[] {6, 2, -2, -6}, alloc, 0, 0, null, 5000 + run);
                ContentResult r = ContentAnalysis.analyse(c.input(), s);
                if (r.pValue() < 0.05) detected++;
                for (int j = 0; j < 4; j++) {
                    double[] ci = r.interval95(j);
                    if (ci[0] <= c.trueSeverity[j] && c.trueSeverity[j] <= ci[1]) covered++;
                    total++;
                    sse += Math.pow(r.severity()[j] - c.trueSeverity[j], 2);
                }
            }
            System.out.printf(Locale.ROOT, "%-10s false alarms: bootstrap %d/%d, placebo %d/%d | detected %d/%d | "
                    + "coverage %.0f%% | RMSE %.2f%n", alloc, bootAlarms, runs, placeboAlarms, runs, detected, runs,
                    100.0 * covered / total, Math.sqrt(sse / total));
            System.out.println("           mean severity under no effect (pile 1 strongest): " + java.util.Arrays.toString(
                    java.util.Arrays.stream(nullMean).map(v -> Math.round(v * 100) / 100.0).toArray()));
        }
    }
}
