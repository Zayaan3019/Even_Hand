package in.ac.iitm.evenhand.design.sim;

import in.ac.iitm.evenhand.core.AllocationEvidence;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds marking arrangements of a known shape, so the audit can be tested against
 * cases whose right answer we worked out in advance rather than read off its output.
 *
 * <p>Written before the estimator, deliberately. A generator built after the thing it
 * tests tends to generate what that thing already handles; built first, it is the
 * specification. Every arrangement here comes from the proposal's own account of how
 * IIT Madras courses divide marking, plus the pathological cases a platform claiming
 * to accept an arbitrary course has to survive.
 *
 * <p>All methods return a {@link MarkingDesign}, which carries no scores. That is the
 * right level for testing the audit, because the audit's verdict does not depend on
 * any mark. Students are dealt in roll-number order &mdash; student {@code s} is the
 * {@code s}-th roll number &mdash; and each design carries the {@link AllocationEvidence}
 * that ordering implies, just as an ingested design carries the evidence its real roll
 * numbers imply.
 */
public final class DesignGenerator {

    private DesignGenerator() {
    }

    /**
     * Each assistant marks every question for a random subset of students.
     *
     * <p>Every assistant is seen on every question, so severity and difficulty
     * separate cleanly, and because the piles were drawn at random the confound
     * between an assistant's severity and the ability of the students they happened
     * to receive vanishes in expectation.
     */
    public static MarkingDesign scriptWiseRandom(int students, int questions, int graders, long seed) {
        Random rng = new Random(seed);
        List<DesignCell> cells = new ArrayList<>();
        for (int s = 0; s < students; s++) {
            int g = rng.nextInt(graders);
            for (int q = 0; q < questions; q++) {
                cells.add(new DesignCell(s, q, g));
            }
        }
        return build(cells, students, questions, graders);
    }

    /**
     * Each assistant marks every question for a contiguous block of roll numbers.
     *
     * <p>Structurally identical to the random split as far as the audit is concerned,
     * and materially different for the conclusion: severity is now confounded with
     * whatever the roll-number ordering correlates with. The allocation evidence is
     * what notices the split was not random, and says so.
     */
    public static MarkingDesign scriptWiseFixedBlocks(int students, int questions, int graders) {
        List<DesignCell> cells = new ArrayList<>();
        int blockSize = (int) Math.ceil(students / (double) graders);
        for (int s = 0; s < students; s++) {
            int g = Math.min(graders - 1, s / blockSize);
            for (int q = 0; q < questions; q++) {
                cells.add(new DesignCell(s, q, g));
            }
        }
        return build(cells, students, questions, graders);
    }

    /**
     * Each assistant marks one question across the whole class.
     *
     * <p>The arrangement teaching-practice guidance recommends and Gradescope's
     * per-question assignment encourages. Severity is not estimable at all: an
     * assistant's severity appears only ever alongside the difficulty of their one
     * question, so only the sum is identified. And it cannot have harmed anyone, because
     * every student met the same assistant on every question. The audit must say both.
     *
     * <p>Questions are dealt round-robin when there are more questions than graders,
     * which keeps each question owned by exactly one assistant.
     */
    public static MarkingDesign questionWise(int students, int questions, int graders) {
        List<DesignCell> cells = new ArrayList<>();
        for (int s = 0; s < students; s++) {
            for (int q = 0; q < questions; q++) {
                cells.add(new DesignCell(s, q, q % graders));
            }
        }
        return build(cells, students, questions, graders);
    }

    /**
     * Question-wise marking with the order each script was marked in recorded.
     *
     * @param alternateReversed when false, every assistant works through the stack in the
     *                          same roll-number order, so a script's position is the same on
     *                          every question; when true, the assistants on odd-numbered
     *                          questions work through it in reverse
     */
    public static MarkingDesign questionWiseWithStackOrder(int students, int questions, int graders,
                                                           boolean alternateReversed) {
        List<DesignCell> cells = new ArrayList<>();
        for (int s = 0; s < students; s++) {
            for (int q = 0; q < questions; q++) {
                int position = (alternateReversed && q % 2 == 1) ? students - s : s + 1;
                cells.add(new DesignCell(s, q, q % graders, 0, position));
            }
        }
        return build(cells, students, questions, graders);
    }

    /**
     * Question-wise marking with a handful of scripts marked a second time by a
     * different assistant, as a check that does not change the mark of record.
     *
     * <p>This is the arrangement of the course this project was built for. Each
     * double-marked script links the two assistants who marked it; enough links and the
     * whole panel is on one scale. Because the second marks do not count, every student's
     * marks of record still come from one assistant per question, so nobody was exposed
     * to a different mix of assistants.
     *
     * @param doubleMarkedPerQuestion scripts per question given a second marker
     */
    public static MarkingDesign questionWiseWithDoubleMarking(int students, int questions,
                                                              int graders, int doubleMarkedPerQuestion) {
        List<DesignCell> cells = new ArrayList<>(questionWise(students, questions, graders).cells());
        for (int q = 0; q < questions; q++) {
            int owner = q % graders;
            int second = (owner + 1) % graders;
            if (second == owner) continue;
            for (int k = 0; k < doubleMarkedPerQuestion && k < students; k++) {
                cells.add(new DesignCell(k, q, second).asSecondMark());
            }
        }
        return build(cells, students, questions, graders);
    }

    /**
     * Some assistants span questions, others own one. The realistic middle, and what
     * an instructor who split assignments one way and the end-semester exam another
     * actually produces.
     */
    public static MarkingDesign mixed(int students, int questions, int graders, long seed) {
        Random rng = new Random(seed);
        List<DesignCell> cells = new ArrayList<>();
        int spanning = Math.max(1, graders / 2);
        for (int s = 0; s < students; s++) {
            for (int q = 0; q < questions; q++) {
                int g = (q < questions / 2)
                        ? rng.nextInt(spanning)                       // script-wise among the first few
                        : spanning + (q % Math.max(1, graders - spanning)); // question-wise for the rest
                cells.add(new DesignCell(s, q, Math.min(g, graders - 1)));
            }
        }
        return build(cells, students, questions, graders);
    }

    /**
     * One person marked everything. There is no grader facet, no severity, and no
     * project; the audit must say so plainly rather than returning a zero.
     */
    public static MarkingDesign singleMarker(int students, int questions) {
        List<DesignCell> cells = new ArrayList<>();
        for (int s = 0; s < students; s++) {
            for (int q = 0; q < questions; q++) {
                cells.add(new DesignCell(s, q, 0));
            }
        }
        return build(cells, students, questions, 1);
    }

    /**
     * Two groups of assistants marking two disjoint groups of students on disjoint
     * questions, with nothing shared between them.
     *
     * <p>Within each island severities are comparable; across islands they are not,
     * and no anchoring makes them so. The audit should report both groups, rather than
     * silently reporting all of them on one scale or calling one of them "blocked".
     */
    public static MarkingDesign disconnectedIslands(int studentsPerIsland, int questionsPerIsland,
                                                    int gradersPerIsland) {
        List<DesignCell> cells = new ArrayList<>();
        for (int island = 0; island < 2; island++) {
            int sOff = island * studentsPerIsland;
            int qOff = island * questionsPerIsland;
            int gOff = island * gradersPerIsland;
            for (int s = 0; s < studentsPerIsland; s++) {
                int g = gOff + (s % gradersPerIsland);
                for (int q = 0; q < questionsPerIsland; q++) {
                    cells.add(new DesignCell(sOff + s, qOff + q, g));
                }
            }
        }
        return build(cells, 2 * studentsPerIsland, 2 * questionsPerIsland, 2 * gradersPerIsland);
    }

    /**
     * Two islands joined by a single script second-marked across them: the smallest
     * repair that can work.
     */
    public static MarkingDesign disconnectedIslandsWithOneBridge(int studentsPerIsland,
                                                                 int questionsPerIsland,
                                                                 int gradersPerIsland) {
        MarkingDesign islands = disconnectedIslands(studentsPerIsland, questionsPerIsland, gradersPerIsland);
        List<DesignCell> cells = new ArrayList<>(islands.cells());
        // An assistant from the second island second-marks a script from the first.
        cells.add(new DesignCell(0, 0, gradersPerIsland).asSecondMark());
        return build(cells, islands.studentCount(), islands.questionCount(), islands.graderCount());
    }

    /**
     * Question-wise marking in which the assistant assigned to a question rotates
     * across blocks of students.
     *
     * <p>Nobody marks a script twice, yet every assistant is seen on several questions
     * and every student is seen by several assistants, and that crossing alone puts the
     * whole panel on one scale. Whether it is harmless depends on the questions: when
     * every student meets each assistant on questions of equal worth, nobody's mix of
     * assistants differs, but over questions of unequal worth the rotation itself exposes
     * students differently. That is why it is offered for feedback, not as a repair.
     */
    public static MarkingDesign rotatingCrossed(int students, int questions, int graders, int blocks) {
        List<DesignCell> cells = new ArrayList<>();
        int blockSize = (int) Math.ceil(students / (double) blocks);
        for (int s = 0; s < students; s++) {
            int block = Math.min(blocks - 1, s / blockSize);
            for (int q = 0; q < questions; q++) {
                cells.add(new DesignCell(s, q, (q + block) % graders));
            }
        }
        return build(cells, students, questions, graders);
    }

    private static MarkingDesign build(List<DesignCell> cells, int students, int questions, int graders) {
        MarkingDesign design = MarkingDesign.of(cells,
                labels("Student", students),
                labels("Q", questions),
                labels("Assistant ", graders));
        int[] rollOrder = new int[students];
        for (int s = 0; s < students; s++) rollOrder[s] = s;
        return design.withAllocationEvidence(
                AllocationEvidence.fromRollOrder(cells, students, graders, rollOrder, new String[students]));
    }

    private static List<String> labels(String prefix, int n) {
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(prefix + (i + 1));
        }
        return out;
    }
}
