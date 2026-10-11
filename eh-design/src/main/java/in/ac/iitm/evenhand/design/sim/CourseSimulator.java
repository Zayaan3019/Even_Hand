package in.ac.iitm.evenhand.design.sim;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Generates realistic IIT Madras courses whose answers are known, for testing and for
 * training the estimator's settings. Real marks are private, so this is the dataset.
 *
 * <p>Every script is marked <strong>twice</strong> in simulation, with the same luck:
 * once by the real assistant, and once by a perfectly average assistant. The second
 * gives the mark the student deserved, so for every simulated student we know the grade
 * they should have got. That is the ground truth a real course can never provide.
 *
 * <p>The marks are not generated from the model the estimator fits. They come from a
 * logistic curve (as in item-response theory), are read with marking noise, and are
 * rounded to half marks and clipped at zero and full marks, so the benchmark tests the
 * estimator on data that breaks its assumptions in the ways real marks do.
 *
 * <p>The seven course shapes are modelled on how marking is divided at IITM: see
 * {@link Archetype}. Roll numbers follow the institute's format (department, year,
 * programme, serial: {@code CE23B092}).
 */
public final class CourseSimulator {

    /** How one assessment's scripts are divided among the assistants. */
    public enum Allocation {
        /** Each assistant marks every question for one contiguous block of roll numbers. */
        SCRIPT_ROLL_BLOCKS,
        /** Each assistant marks every question for a random pile of scripts. */
        SCRIPT_RANDOM,
        /** Each assistant marks the scripts of whole departments (lab batches). */
        SCRIPT_BY_DEPARTMENT,
        /** Each assistant marks one tutorial group, formed at random, all semester. */
        SCRIPT_TUTORIAL_GROUPS,
        /** Each question is marked by one assistant for the whole class. */
        QUESTION_WISE,
        /** Question-wise, but which assistant marks a question rotates across roll-number blocks. */
        QUESTION_WISE_ROTATED,
        /** Two sections with separate assistant teams that never mark each other's scripts. */
        TWO_SECTIONS
    }

    /** One assessment: its name, weight in the course grade, question maxima and allocation. */
    public record AssessmentPlan(String name, double weight, int[] maxima, Allocation allocation,
                                 double doubleMarkRate) {}

    /** A course shape. Sizes are drawn between {@code minStudents} and {@code maxStudents}. */
    public enum Archetype {
        CE_CORE("Civil Engineering core course, roll-number piles for quizzes, question-wise end-sem",
                80, 110, 3, new String[] { "CE" }, new char[] { 'B' }, 23, false,
                new AssessmentPlan("Quiz 1", 25, new int[] { 5, 5, 5, 5 }, Allocation.SCRIPT_ROLL_BLOCKS, 0),
                new AssessmentPlan("Quiz 2", 25, new int[] { 5, 5, 5, 5 }, Allocation.SCRIPT_ROLL_BLOCKS, 0),
                new AssessmentPlan("End-sem", 50, new int[] { 10, 10, 10, 15, 15 }, Allocation.QUESTION_WISE, 0.10)),
        FIRST_YEAR_CORE("Large first-year core course, random script piles, eight assistants",
                380, 450, 8, new String[] { "AE", "BE", "CE", "CH", "CS", "ED", "EE", "EP", "ME", "MM", "NA", "PH" },
                new char[] { 'B' }, 26, false,
                new AssessmentPlan("Quiz 1", 20, new int[] { 10, 10, 10 }, Allocation.SCRIPT_RANDOM, 0),
                new AssessmentPlan("Quiz 2", 20, new int[] { 10, 10, 10 }, Allocation.SCRIPT_RANDOM, 0),
                new AssessmentPlan("End-sem", 60, new int[] { 12, 12, 12, 12, 12 }, Allocation.SCRIPT_RANDOM, 0)),
        SMALL_ELECTIVE("Small elective, two assistants, one per question, no overlap",
                25, 45, 2, new String[] { "CE", "ME", "AM" }, new char[] { 'B' }, 22, false,
                new AssessmentPlan("Quiz", 30, new int[] { 4, 4, 4, 4, 4 }, Allocation.QUESTION_WISE, 0),
                new AssessmentPlan("End-sem", 70, new int[] { 10, 10, 10, 10, 10 }, Allocation.QUESTION_WISE, 0)),
        PG_ROTATED("Postgraduate course, question-wise marking rotated across roll blocks",
                50, 70, 4, new String[] { "CE" }, new char[] { 'M', 'S' }, 25, false,
                new AssessmentPlan("Quiz", 40, new int[] { 5, 5, 5, 5 }, Allocation.QUESTION_WISE_ROTATED, 0),
                new AssessmentPlan("End-sem", 60, new int[] { 10, 10, 10, 10, 10, 10 }, Allocation.QUESTION_WISE_ROTATED, 0)),
        LAB_BATCHES("Multi-department course marked by lab batch, batches differ in ability",
                120, 160, 4, new String[] { "AE", "CE", "ME", "NA" }, new char[] { 'B' }, 24, true,
                new AssessmentPlan("Quiz 1", 25, new int[] { 5, 5, 5, 5 }, Allocation.SCRIPT_BY_DEPARTMENT, 0),
                new AssessmentPlan("Quiz 2", 25, new int[] { 5, 5, 5, 5 }, Allocation.SCRIPT_BY_DEPARTMENT, 0),
                new AssessmentPlan("End-sem", 50, new int[] { 10, 10, 10, 10, 10 }, Allocation.SCRIPT_BY_DEPARTMENT, 0)),
        TUTORIAL_GROUPS("Course marked by tutorial group: random groups, same assistant all semester",
                100, 140, 5, new String[] { "CE", "ME", "CH", "MM" }, new char[] { 'B' }, 24, false,
                new AssessmentPlan("Quiz 1", 20, new int[] { 5, 5, 5, 5 }, Allocation.SCRIPT_TUTORIAL_GROUPS, 0),
                new AssessmentPlan("Quiz 2", 20, new int[] { 5, 5, 5, 5 }, Allocation.SCRIPT_TUTORIAL_GROUPS, 0),
                new AssessmentPlan("End-sem", 60, new int[] { 10, 10, 10, 10, 10, 10 }, Allocation.SCRIPT_TUTORIAL_GROUPS, 0)),
        TWO_SECTIONS("Two slot sections with separate assistant teams that never overlap",
                150, 200, 4, new String[] { "CE", "ME", "EE", "CH" }, new char[] { 'B' }, 24, false,
                new AssessmentPlan("Quiz 1", 25, new int[] { 5, 5, 5, 5 }, Allocation.TWO_SECTIONS, 0),
                new AssessmentPlan("Quiz 2", 25, new int[] { 5, 5, 5, 5 }, Allocation.TWO_SECTIONS, 0),
                new AssessmentPlan("End-sem", 50, new int[] { 10, 10, 10, 10, 10 }, Allocation.TWO_SECTIONS, 0));

        public final String description;
        final int minStudents, maxStudents, assistants, year;
        final String[] departments;
        final char[] programmes;
        final boolean departmentsDiffer;
        final AssessmentPlan[] plan;

        Archetype(String description, int minStudents, int maxStudents, int assistants, String[] departments,
                  char[] programmes, int year, boolean departmentsDiffer, AssessmentPlan... plan) {
            this.description = description;
            this.minStudents = minStudents;
            this.maxStudents = maxStudents;
            this.assistants = assistants;
            this.departments = departments;
            this.programmes = programmes;
            this.year = year;
            this.departmentsDiffer = departmentsDiffer;
            this.plan = plan;
        }

        public Map<String, Double> weights() {
            Map<String, Double> w = new LinkedHashMap<>();
            for (AssessmentPlan p : plan) w.put(p.name(), p.weight());
            return w;
        }
    }

    /**
     * One marked answer. {@code fairMarks} is what a perfectly average assistant would have
     * awarded the same answer with the same luck: the ground truth.
     */
    public record Row(String roll, String assessment, String question, int maxMarks, double marks,
                      String assistant, boolean ofRecord, double fairMarks) {}

    /**
     * A simulated course and its answers.
     *
     * @param trueSeverity per assistant, the percentage points of each question's maximum
     *                     they actually took off relative to an average assistant
     *                     (positive = harsh), measured on the scripts they marked
     * @param severitySd   the spread of severity this course was generated with, in logits;
     *                     zero means the assistants were truly identical
     */
    public record SimulatedCourse(String name, Archetype archetype, long seed, List<Row> rows,
                                  Map<String, Double> weights, Map<String, Double> trueSeverity,
                                  double severitySd) {

        /** The marks exactly as an instructor's export would look: no truth in it. */
        public String marksCsv() {
            return csv(false);
        }

        /**
         * The same file with every mark of record replaced by the mark an average assistant
         * would have written. Graded, it gives the grade each student deserved.
         */
        public String fairMarksCsv() {
            return csv(true);
        }

        private String csv(boolean fair) {
            StringBuilder out = new StringBuilder("roll_no,assessment,question,max_marks,marks,ta,role\n");
            for (Row r : rows) {
                out.append(r.roll()).append(',').append(r.assessment()).append(',').append(r.question()).append(',')
                   .append(r.maxMarks()).append(',').append(fmt(fair && r.ofRecord() ? r.fairMarks() : r.marks()))
                   .append(',').append(r.assistant()).append(',').append(r.ofRecord() ? "record" : "second").append('\n');
            }
            return out.toString();
        }

        public void writeMarksCsv(Path file) throws IOException {
            Files.writeString(file, marksCsv(), StandardCharsets.UTF_8);
        }

        /** Writes the answers, kept apart from the marks. */
        public void writeTruthCsv(Path file) throws IOException {
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                out.write("ta,true_severity_pct\n");
                for (Map.Entry<String, Double> e : trueSeverity.entrySet()) {
                    out.write(e.getKey() + ',' + String.format(Locale.ROOT, "%.3f", e.getValue()) + '\n');
                }
            }
        }
    }

    private static final double[] SEVERITY_LEVELS = { 0.0, 0.2, 0.35, 0.5 };

    private CourseSimulator() {
    }

    /** A course of the given shape whose assistants differ by a spread chosen from the seed. */
    public static SimulatedCourse simulate(Archetype archetype, long seed) {
        double sd = SEVERITY_LEVELS[(int) Math.floorMod(seed, SEVERITY_LEVELS.length)];
        return simulate(archetype, seed, sd);
    }

    public static SimulatedCourse simulate(Archetype archetype, long seed, double severitySd) {
        SplittableRandom rng = new SplittableRandom(seed * 31 + archetype.ordinal());
        int n = archetype.minStudents + rng.nextInt(archetype.maxStudents - archetype.minStudents + 1);

        // Students: roll numbers in IITM format, ability on the logit scale.
        List<String> rolls = rollNumbers(archetype, n, rng);
        Map<String, Double> deptShift = new LinkedHashMap<>();
        for (String d : archetype.departments) {
            deptShift.put(d, archetype.departmentsDiffer ? rng.nextGaussian() * 0.35 : 0.0);
        }
        double[] ability = new double[n];
        for (int i = 0; i < n; i++) {
            ability[i] = 0.4 + deptShift.get(rolls.get(i).substring(0, 2)) + rng.nextGaussian() * 1.0;
        }

        // Assistants: severities centred on the panel, so "average assistant" means severity 0.
        int j = archetype.assistants;
        String[] names = new String[j];
        double[] severity = new double[j];
        for (int g = 0; g < j; g++) {
            names[g] = String.format(Locale.ROOT, "TA%02d", g + 1);
            severity[g] = rng.nextGaussian() * severitySd;
        }
        double bar = 0;
        for (double s : severity) bar += s;
        for (int g = 0; g < j; g++) severity[g] -= bar / j;
        double[] readingNoise = new double[j];
        for (int g = 0; g < j; g++) readingNoise[g] = 0.03 + rng.nextDouble() * 0.04;

        List<Row> rows = new ArrayList<>();
        double[] effectSum = new double[j];
        int[] effectCount = new int[j];
        int[] sortedByRoll = order(rolls);
        int[] tutorialGroup = new int[n];
        for (int i = 0; i < n; i++) tutorialGroup[i] = rng.nextInt(j);

        for (AssessmentPlan plan : archetype.plan) {
            int questions = plan.maxima().length;
            double[] difficulty = new double[questions];
            for (int q = 0; q < questions; q++) difficulty[q] = rng.nextGaussian() * 0.7;
            int[] pile = plan.allocation() == Allocation.SCRIPT_TUTORIAL_GROUPS
                    ? tutorialGroup
                    : piles(plan.allocation(), rolls, sortedByRoll, j, rng);

            for (int i = 0; i < n; i++) {
                if (rng.nextDouble() < 0.01) continue;   // absent from this assessment
                for (int q = 0; q < questions; q++) {
                    int marker = marker(plan.allocation(), pile[i], q, j);
                    double luck = rng.nextGaussian() * 0.4;            // how this answer went on the day
                    double noise = rng.nextGaussian();                 // how this reading went
                    String qName = "Q" + (q + 1);
                    int max = plan.maxima()[q];

                    double fairP = logistic(ability[i] - difficulty[q] + luck);
                    double markedP = logistic(ability[i] - difficulty[q] + luck - severity[marker]);
                    double fair = mark(fairP, noise * readingNoise[marker], max);
                    double marked = mark(markedP, noise * readingNoise[marker], max);
                    rows.add(new Row(rolls.get(i), plan.name(), qName, max, marked, names[marker], true, fair));
                    effectSum[marker] += 100.0 * (fairP - markedP);
                    effectCount[marker]++;

                    if (rng.nextDouble() < plan.doubleMarkRate()) {
                        int second = (marker + 1 + rng.nextInt(j - 1)) % j;
                        double secondP = logistic(ability[i] - difficulty[q] + luck - severity[second]);
                        double secondMark = mark(secondP, rng.nextGaussian() * readingNoise[second], max);
                        rows.add(new Row(rolls.get(i), plan.name(), qName, max, secondMark, names[second], false, fair));
                    }
                }
            }
        }

        Map<String, Double> truth = new LinkedHashMap<>();
        for (int g = 0; g < j; g++) {
            truth.put(names[g], effectCount[g] == 0 ? 0.0 : effectSum[g] / effectCount[g]);
        }
        String name = archetype.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + seed;
        return new SimulatedCourse(name, archetype, seed, rows, archetype.weights(), truth, severitySd);
    }

    /** The mark a reading produces: on the curve, plus noise, in half marks, within 0..max. */
    private static double mark(double p, double noise, int max) {
        double raw = max * (p + noise);
        double halves = Math.round(raw * 2) / 2.0;
        return Math.max(0, Math.min(max, halves));
    }

    private static double logistic(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    private static List<String> rollNumbers(Archetype a, int n, SplittableRandom rng) {
        Map<String, Integer> serial = new LinkedHashMap<>();
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String dept = a.departments[rng.nextInt(a.departments.length)];
            char prog = a.programmes[rng.nextInt(a.programmes.length)];
            String key = dept + prog;
            int next = serial.merge(key, 1 + rng.nextInt(3), Integer::sum);
            out.add(String.format(Locale.ROOT, "%s%02d%c%03d", dept, a.year % 100, prog, next));
        }
        return out;
    }

    private static int[] order(List<String> rolls) {
        Integer[] idx = new Integer[rolls.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, Comparator.comparing(rolls::get));
        int[] out = new int[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = idx[i];
        return out;
    }

    /** For script-wise and sectioned allocations: which pile each student's script went to. */
    private static int[] piles(Allocation allocation, List<String> rolls, int[] byRoll, int j, SplittableRandom rng) {
        int n = rolls.size();
        int[] pile = new int[n];
        switch (allocation) {
            case SCRIPT_ROLL_BLOCKS, QUESTION_WISE_ROTATED -> {
                for (int rank = 0; rank < n; rank++) pile[byRoll[rank]] = (int) ((long) rank * j / n);
            }
            case SCRIPT_RANDOM -> {
                for (int i = 0; i < n; i++) pile[i] = rng.nextInt(j);
            }
            case SCRIPT_BY_DEPARTMENT -> {
                Map<String, Integer> batch = new LinkedHashMap<>();
                for (String r : rolls.stream().sorted().toList()) {
                    batch.putIfAbsent(r.substring(0, 2), batch.size() % j);
                }
                for (int i = 0; i < n; i++) pile[i] = batch.get(rolls.get(i).substring(0, 2));
            }
            case TWO_SECTIONS -> {
                // Section by roll order (slot A, slot B); within a section, random piles
                // among that section's own assistants.
                int half = j / 2;
                for (int rank = 0; rank < n; rank++) {
                    int student = byRoll[rank];
                    boolean sectionA = rank < n / 2;
                    pile[student] = sectionA ? rng.nextInt(half) : half + rng.nextInt(j - half);
                }
            }
            case QUESTION_WISE, SCRIPT_TUTORIAL_GROUPS -> { /* irrelevant, or fixed for the semester */ }
        }
        return pile;
    }

    private static int marker(Allocation allocation, int pile, int question, int j) {
        return switch (allocation) {
            case QUESTION_WISE -> question % j;
            case QUESTION_WISE_ROTATED -> (question + pile) % j;
            default -> pile;
        };
    }

    private static String fmt(double x) {
        return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
    }
}
