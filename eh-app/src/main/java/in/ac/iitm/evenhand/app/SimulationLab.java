package in.ac.iitm.evenhand.app;

import in.ac.iitm.evenhand.core.CutoffPolicy;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.LetterGrade;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.design.AuditReport;
import in.ac.iitm.evenhand.design.sim.CourseSimulator;
import in.ac.iitm.evenhand.estimate.Settings;
import in.ac.iitm.evenhand.outcome.Outcome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Simulation Lab: one synthetic IITM course, run through the real pipeline, with the
 * answers shown beside the estimates.
 *
 * <p>Nothing here is a mock-up. The course comes from {@link CourseSimulator}, and the audit,
 * estimate and regrading are the same calls the instructor's run makes ({@link Benchmark#prepare},
 * {@link Benchmark#fit}, {@link Outcome#assess}). What a real course can never show, and the lab
 * can, is the truth: each TA's actual severity and each student's deserved grade.
 */
final class SimulationLab {

    private SimulationLab() {
    }

    /** Runs one course and returns everything the lab page draws, as JSON. */
    static String run(String shape, double severitySd, long seed) {
        long start = System.nanoTime();
        CourseSimulator.Archetype archetype =
                CourseSimulator.Archetype.valueOf(shape.toUpperCase(Locale.ROOT).replace('-', '_'));
        if (!(severitySd >= 0 && severitySd <= 1.5)) throw new IllegalArgumentException("spread must be 0 to 1.5");
        CourseSimulator.SimulatedCourse course = CourseSimulator.simulate(archetype, seed, severitySd);
        CutoffPolicy rule = CutoffPolicy.iitmConventionalRelative();
        Benchmark.Prepared p = Benchmark.prepare(course, rule);
        MarkingDesign design = p.design();
        Main.KeyLookup key = Main.KeyLookup.of(p.ingest().keyFileContents());

        Json j = new Json();
        j.str("shape", archetype.name().toLowerCase(Locale.ROOT).replace('_', '-'))
         .str("description", archetype.description)
         .num("students", design.studentCount()).num("assistants", design.graderCount())
         .num("seed", seed).num("spread", severitySd)
         .str("verdict", AuditReport.shortName(p.verdict()))
         .str("headline", p.verdict().headline())
         .str("exposure", p.verdict().exposure().headline());

        int moved = 0;
        for (int i = 0; i < p.markedGrades().length; i++) if (p.markedGrades()[i] != p.trueGrades()[i]) moved++;
        j.num("trulyMoved", moved);

        FittedModel m = null;
        Outcome.Result o = null;
        double[] truth = p.trueSeverity().clone();
        if (p.verdict().permitsEstimation()) {
            m = Benchmark.fit(p, Settings.defaults().withDraws(100, 39));
            truth = Benchmark.centredTruth(p, m);
            o = Outcome.assess(m, p.ingest().responses(), design, p.weights(), rule, Outcome.Gate.standard());
        } else {
            double bar = 0;
            for (double t : truth) bar += t / truth.length;
            for (int g = 0; g < truth.length; g++) truth[g] -= bar;
        }
        j.bool("estimated", m != null);

        // TAs: truth, estimate, interval.
        StringBuilder tas = new StringBuilder("[");
        for (int g = 0; g < design.graderCount(); g++) {
            Json t = new Json().str("label", design.graderLabels().get(g))
                    .str("name", key.real(design.graderLabels().get(g)))
                    .num("truth", truth[g]).num("marks", countMarks(design, g));
            if (m != null) {
                double[] ci = m.interval95(g);
                t.num("estimate", m.severity(g)).num("lo", ci[0]).num("hi", ci[1]);
            }
            tas.append(g == 0 ? "" : ",").append(t);
        }
        j.raw("tas", tas.append(']').toString());

        // Grades: as marked, deserved (truth), and as corrected by the tool.
        j.raw("grades", grades(p.markedGrades(), p.trueGrades(), o == null ? null : o.gradesCorrected()));

        if (o != null) {
            j.num("pValue", m.noEffect().pValue()).num("share", o.assistantShare())
             .num("changed", o.changed()).num("changedLow", o.changedLow()).num("changedHigh", o.changedHigh());
            if (o.shortlist() instanceof Outcome.Emitted e) {
                StringBuilder rows = new StringBuilder("[");
                int right = 0;
                for (Outcome.Row r : e.rows()) {
                    int i = design.studentLabels().indexOf(r.student());
                    boolean truly = p.markedGrades()[i] != p.trueGrades()[i];
                    if (truly) right++;
                    rows.append(rows.length() == 1 ? "" : ",").append(new Json()
                            .str("student", r.student()).str("roll", key.real(r.student()))
                            .str("from", r.gradeAsMarked().name()).str("to", r.likelyGrade().name())
                            .str("deserved", p.trueGrades()[i].name())
                            .num("p", r.probability()).bool("truly", truly));
                }
                int baseline = 0;
                for (int i : Benchmark.nearestBoundary(p, rule, e.rows().size())) {
                    if (p.markedGrades()[i] != p.trueGrades()[i]) baseline++;
                }
                j.raw("recheck", rows.append(']').toString()).num("recheckRight", right).num("baselineRight", baseline);
            } else {
                j.str("refusal", ((Outcome.Refused) o.shortlist()).reason());
            }
        } else {
            j.str("refusal", "The audit refused estimation: " + p.verdict().headline());
        }

        j.raw("allocation", allocation(design, key));
        j.num("elapsedMs", (System.nanoTime() - start) / 1_000_000);
        return j.toString();
    }

    private static int countMarks(MarkingDesign d, int grader) {
        int c = 0;
        for (DesignCell cell : d.cells()) if (cell.grader() == grader) c++;
        return c;
    }

    private static String grades(LetterGrade[] marked, LetterGrade[] deserved, LetterGrade[] corrected) {
        StringBuilder sb = new StringBuilder("{\"letters\":[");
        LetterGrade[] letters = LetterGrade.awardable();
        for (int k = 0; k < letters.length; k++) sb.append(k == 0 ? "" : ",").append('"').append(letters[k]).append('"');
        sb.append("],\"marked\":").append(counts(marked)).append(",\"deserved\":").append(counts(deserved));
        if (corrected != null) sb.append(",\"corrected\":").append(counts(corrected));
        return sb.append('}').toString();
    }

    private static String counts(LetterGrade[] grades) {
        StringBuilder sb = new StringBuilder("[");
        LetterGrade[] letters = LetterGrade.awardable();
        for (int k = 0; k < letters.length; k++) {
            int c = 0;
            for (LetterGrade g : grades) if (g == letters[k]) c++;
            sb.append(k == 0 ? "" : ",").append(c);
        }
        return sb.append(']').toString();
    }

    /** Who marked what: students in roll order by questions, the TA of record in each cell (-1 absent). */
    private static String allocation(MarkingDesign d, Main.KeyLookup key) {
        int[][] grid = new int[d.studentCount()][d.questionCount()];
        boolean[][] second = new boolean[d.studentCount()][d.questionCount()];
        for (int[] row : grid) java.util.Arrays.fill(row, -1);
        for (DesignCell c : d.cells()) {
            if (c.ofRecord()) grid[c.student()][c.question()] = c.grader();
            else second[c.student()][c.question()] = true;
        }
        List<Integer> order = new ArrayList<>();
        for (int s = 0; s < d.studentCount(); s++) order.add(s);
        order.sort(Comparator.comparing(s -> key.real(d.studentLabels().get(s))));

        StringBuilder sb = new StringBuilder("{\"questions\":[");
        for (int q = 0; q < d.questionCount(); q++) sb.append(q == 0 ? "" : ",").append(Json.quote(d.questionLabels().get(q)));
        sb.append("],\"rolls\":[");
        for (int k = 0; k < order.size(); k++) sb.append(k == 0 ? "" : ",").append(Json.quote(key.real(d.studentLabels().get(order.get(k)))));
        sb.append("],\"cells\":[");
        for (int k = 0; k < order.size(); k++) {
            int s = order.get(k);
            sb.append(k == 0 ? "\"" : ",\"");
            for (int q = 0; q < d.questionCount(); q++) {
                // One character per cell: '.' absent, 'a'.. a TA of record, 'A'.. the same TA when a second mark exists.
                int g = grid[s][q];
                sb.append(g < 0 ? '.' : (char) ((second[s][q] ? 'A' : 'a') + g));
            }
            sb.append('"');
        }
        return sb.append("]}").toString();
    }

    /** A minimal JSON object writer; the lab needs nothing more. */
    static final class Json {
        private final StringBuilder sb = new StringBuilder();

        Json str(String k, String v) { return raw(k, quote(v)); }
        Json num(String k, double v) {
            return raw(k, Double.isFinite(v) ? (v == Math.rint(v) && Math.abs(v) < 1e15
                    ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.4f", v)) : "null");
        }
        Json bool(String k, boolean v) { return raw(k, String.valueOf(v)); }
        Json raw(String k, String json) {
            sb.append(sb.length() == 0 ? "" : ",").append(quote(k)).append(':').append(json);
            return this;
        }

        static String quote(String s) {
            StringBuilder q = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> q.append("\\\"");
                    case '\\' -> q.append("\\\\");
                    case '\n' -> q.append("\\n");
                    default -> {
                        if (c < 0x20) q.append(String.format("\\u%04x", (int) c)); else q.append(c);
                    }
                }
            }
            return q.append('"').toString();
        }

        @Override
        public String toString() {
            return "{" + sb + "}";
        }
    }
}
