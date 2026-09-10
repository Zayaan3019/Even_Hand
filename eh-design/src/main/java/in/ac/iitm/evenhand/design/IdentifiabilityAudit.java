package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.IdentifiabilityCertificate;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.numerics.ExactRowSpace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The platform's first output, and the reason it can be trusted on a course it has
 * never seen: a verdict on what the data can and cannot tell you.
 *
 * <p>It is a static analysis over a marking arrangement. Like a type checker it runs
 * before the expensive stage, refuses input that stage would turn into nonsense, and
 * names the construct at fault rather than merely failing. And like a good type
 * checker it suggests the repair.
 *
 * <p><strong>It needs no marks.</strong> Everything below is computed from
 * {@link MarkingDesign}, which carries who marked what and no scores at all. An
 * instructor can be told what his arrangement supports before he shares a single
 * mark, which is the difference between a tool he must trust in advance and one that
 * earns it.
 *
 * <h2>What is being decided</h2>
 *
 * <p>The linear predictor {@code eta = theta_n - delta_i - gamma_j} is unchanged if a
 * constant is added to every ability and every difficulty, and again if one is added
 * to every ability and every severity. That two-dimensional indeterminacy is present
 * in every design and is what anchoring removes. Null directions beyond those two are
 * real confounds in this particular arrangement.
 *
 * <p>Rather than measure the null space and interpret it, the audit asks the question
 * the instructor actually has, once per pair of assistants: <em>is the difference
 * between these two severities estimable?</em> That is the question of whether the
 * vector expressing the difference lies in the row space of the design, and it has an
 * exact yes-or-no answer with no tolerance in it.
 *
 * <h2>The two ways a design fails, which are not the same way</h2>
 *
 * <p>Computing the rank rather than inspecting a connectivity graph surfaces a
 * distinction that matters, because the two arrangements IIT Madras courses use fail
 * differently and only one of them is recoverable.
 *
 * <ul>
 *   <li><strong>Question-wise.</strong> Each assistant marks one question across the
 *       class. Their severity is only ever observed added to that question's
 *       difficulty. No assumption about students touches this, because no student was
 *       marked on that question by anyone else. The excess null dimension is one less
 *       than the number of assistants: not a single severity difference is estimable.</li>
 *   <li><strong>Script-wise.</strong> Each assistant marks a whole pile and nobody
 *       else touches it. Their severity is absorbed into the abilities of the students
 *       in that pile. The rank deficiency is exactly the same size &mdash; as algebra
 *       this is equally fatal &mdash; but it <em>is</em> rescuable, by assuming the
 *       piles were alike in ability. That is what random allocation buys.</li>
 * </ul>
 *
 * <p>The audit separates them by asking the same estimability question a second time
 * against a design whose student columns have been collapsed into one intercept
 * ({@link DesignMatrix#withStudentsCollapsed()}). Collapsing the students <em>is</em>
 * the exchangeability assumption. If a contrast becomes estimable once it is made,
 * the confound was with ability and randomisation is the remedy; if it does not, the
 * confound is with question difficulty and only a second marker on the same question
 * will do.
 *
 * <p>One consequence is worth stating plainly, because it limits the tool: a random
 * split and a fixed roll-number split produce <em>identical</em> designs of identical
 * rank. The audit cannot tell them apart and does not pretend to. What it can see is
 * whether each assistant's pile is a contiguous block of roll numbers, which it
 * reports as evidence for the reader to weigh rather than as a verdict.
 *
 * <h2>Why exactly, and why twice</h2>
 *
 * <p>Rank is computed over the integers by {@link ExactRowSpace}, so the verdict is a
 * fact about the arrangement, not a consequence of a floating-point threshold. The
 * same quantities are computed a second way from the eigenvalues of the Fisher
 * information, and the two must agree on every scenario fixture. The established tool
 * in this area, Facets, uses a graph-joining heuristic derived from Weeks and
 * Williams (1964) whose own manual notes that "there are exotic forms of
 * connectedness which Facets may falsely report as disconnected"; computing the rank
 * itself is what avoids that class of error.
 */
public final class IdentifiabilityAudit {

    private IdentifiabilityAudit() {
    }

    public static AuditVerdict audit(MarkingDesign design) {
        List<Diagnostic> diagnostics = new ArrayList<>(doubleMarkingDiagnostics(design));

        DesignMatrix full = new DesignMatrix(design);
        ExactRowSpace fullSpace = rowSpaceOf(full, design);

        int excess = Math.max(0, fullSpace.nullity() - IdentifiabilityCertificate.NOMINAL_NULLITY);
        IdentifiabilityCertificate certificate = new IdentifiabilityCertificate(
                fullSpace.rank(), full.columns(), excess, design.fingerprint(), fullSpace.nullSpace());

        if (design.graderCount() < 2) {
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH001_SINGLE_MARKER,
                    "All marking was done by one person, so there is no assistant-to-assistant "
                    + "difference for this analysis to be about.",
                    design.graderLabels(),
                    "No repair applies. Severity is only meaningful when marking is divided."));
            return new AuditVerdict.NotEstimable(certificate, diagnostics,
                    "only one person marked this assessment");
        }

        DesignMatrix collapsed = full.withStudentsCollapsed();
        ExactRowSpace collapsedSpace = rowSpaceOf(collapsed, design);

        boolean[][] fromDesign = comparability(full, fullSpace, design.graderCount());
        boolean[][] underExchangeability = comparability(collapsed, collapsedSpace, design.graderCount());

        List<Set<Integer>> designClasses = classes(fromDesign, design.graderCount());
        if (designClasses.size() == 1) {
            return new AuditVerdict.Estimable(
                    new IdentifiabilityCertificate(fullSpace.rank(), full.columns(), 0,
                            design.fingerprint(), fullSpace.nullSpace()),
                    diagnostics);
        }

        diagnostics.addAll(nestingDiagnostics(design, underExchangeability));

        List<Set<Integer>> exchangeabilityClasses = classes(underExchangeability, design.graderCount());
        String allocationEvidence = describeAllocation(design);

        if (exchangeabilityClasses.size() == 1) {
            diagnostics.add(Diagnostic.warning(
                    Diagnostic.Code.EH009_RESTS_ON_EXCHANGEABILITY,
                    "No script was marked twice, so an assistant's severity is only separable from "
                    + "the ability of the students in their pile if those piles are taken to have "
                    + "been alike to begin with. " + allocationEvidence,
                    design.graderLabels(),
                    "Marking a small number of scripts a second time would remove the assumption "
                    + "entirely, and rotating which assistant marks which question across blocks "
                    + "of roll numbers would do the same at no extra marking cost."));
            return new AuditVerdict.EstimableUnderExchangeability(certificate, diagnostics,
                    "that the piles of scripts given to each assistant were alike in ability",
                    allocationEvidence);
        }

        Set<Integer> largest = exchangeabilityClasses.stream()
                .max((a, b) -> Integer.compare(a.size(), b.size()))
                .orElseThrow();

        if (largest.size() < 2) {
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                    "No two assistants can be placed on the same scale. Each one's severity is only "
                    + "ever seen together with the difficulty of the question they marked, so only "
                    + "the sum of the two is estimable, and no amount of student overlap separates "
                    + "them.",
                    design.graderLabels(),
                    "Two repairs work, and the cheaper is worth trying first. Rotate which assistant "
                    + "marks which question across blocks of roll numbers: nobody marks more, and "
                    + "every severity becomes estimable. Failing that, have a second assistant mark "
                    + "a subset of scripts on each question."));
            return new AuditVerdict.NotEstimable(certificate, diagnostics,
                    "no two assistants share enough marking to be compared");
        }

        List<Integer> blocked = new ArrayList<>();
        for (int g = 0; g < design.graderCount(); g++) {
            if (!largest.contains(g)) blocked.add(g);
        }
        boolean largestHoldsWithoutAssumption = true;
        List<Integer> largestList = new ArrayList<>(largest);
        for (int i = 0; i < largestList.size() && largestHoldsWithoutAssumption; i++) {
            for (int j = i + 1; j < largestList.size(); j++) {
                if (!fromDesign[largestList.get(i)][largestList.get(j)]) {
                    largestHoldsWithoutAssumption = false;
                    break;
                }
            }
        }
        if (!largestHoldsWithoutAssumption) {
            diagnostics.add(Diagnostic.warning(
                    Diagnostic.Code.EH009_RESTS_ON_EXCHANGEABILITY,
                    "Even among the assistants that can be compared, the comparison holds only if "
                    + "their piles of scripts are taken to have been alike in ability. "
                    + allocationEvidence,
                    largestList.stream().map(g -> design.graderLabels().get(g)).toList(),
                    "Marking a small number of scripts a second time would remove the assumption."));
        }
        diagnostics.add(Diagnostic.warning(
                Diagnostic.Code.EH002_DISCONNECTED_GRADERS,
                "These assistants are not on a comparable scale with the rest, because no chain of "
                + "shared marking connects them to it.",
                blocked.stream().map(g -> design.graderLabels().get(g)).toList(),
                "One shared unit per unconnected group is the minimum that can work: have one of "
                + "their scripts marked by an assistant from the connected group."));
        return new AuditVerdict.PartiallyEstimable(certificate, diagnostics, largestList, blocked);
    }

    private static ExactRowSpace rowSpaceOf(DesignMatrix matrix, MarkingDesign design) {
        ExactRowSpace space = new ExactRowSpace(matrix.columns());
        for (DesignCell cell : design.cells()) {
            space.addRow(matrix.row(cell));
        }
        return space;
    }

    /**
     * {@code out[a][b]} is true when the difference of those two severities is
     * estimable, which is exactly the question of whether the vector expressing it
     * lies in the row space.
     */
    private static boolean[][] comparability(DesignMatrix matrix, ExactRowSpace space, int graders) {
        boolean[][] out = new boolean[graders][graders];
        for (int a = 0; a < graders; a++) {
            out[a][a] = true;
            for (int b = a + 1; b < graders; b++) {
                boolean yes = space.contains(matrix.severityContrast(a, b));
                out[a][b] = yes;
                out[b][a] = yes;
            }
        }
        return out;
    }

    /** Groups of mutually comparable graders, by transitive closure. */
    private static List<Set<Integer>> classes(boolean[][] comparable, int graders) {
        int[] parent = new int[graders];
        for (int i = 0; i < graders; i++) parent[i] = i;
        for (int a = 0; a < graders; a++) {
            for (int b = a + 1; b < graders; b++) {
                if (comparable[a][b]) union(parent, a, b);
            }
        }
        List<Set<Integer>> out = new ArrayList<>();
        List<Integer> roots = new ArrayList<>();
        for (int g = 0; g < graders; g++) {
            int r = find(parent, g);
            int idx = roots.indexOf(r);
            if (idx < 0) {
                roots.add(r);
                out.add(new LinkedHashSet<>());
                idx = roots.size() - 1;
            }
            out.get(idx).add(g);
        }
        return out;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[rb] = ra;
    }

    /**
     * What the allocation looks like, from the allocation alone.
     *
     * <p>Whether the piles were alike in ability is not knowable without the marks and
     * is not claimed here. Whether they were dealt in roll-number order <em>is</em>
     * visible, and it is the thing most likely to make the assumption false: a split by
     * roll number groups students by admission rank, branch or section, none of which
     * are independent of ability.
     */
    private static String describeAllocation(MarkingDesign design) {
        int contiguous = 0;
        int assessed = 0;
        for (int g = 0; g < design.graderCount(); g++) {
            TreeSet<Integer> pile = new TreeSet<>();
            for (DesignCell c : design.cells()) {
                if (c.grader() == g) pile.add(c.student());
            }
            if (pile.size() < 2) continue;
            assessed++;
            int runs = 1;
            Integer previous = null;
            for (int s : pile) {
                if (previous != null && s != previous + 1) runs++;
                previous = s;
            }
            // A pile dealt at random breaks into roughly as many runs as it has members.
            if (runs <= Math.max(2, pile.size() / 10)) contiguous++;
        }
        if (assessed == 0) {
            return "There is not enough marking per assistant to say how the piles were formed.";
        }
        if (contiguous == assessed) {
            return "Every assistant's pile is a contiguous block of roll numbers, so the split was "
                    + "made by roll number rather than at random, and the assumption that the piles "
                    + "were alike in ability is the one most worth doubting.";
        }
        if (contiguous == 0) {
            return "Assistants' piles are interleaved across roll numbers, which is consistent with "
                    + "a random deal.";
        }
        return contiguous + " of " + assessed + " assistants' piles are contiguous blocks of roll "
                + "numbers, so the split was at least partly made by roll number.";
    }

    /** Reports assistants whose severity is pinned to a single question's difficulty. */
    private static List<Diagnostic> nestingDiagnostics(MarkingDesign design, boolean[][] underExchangeability) {
        List<Diagnostic> out = new ArrayList<>();
        for (int g = 0; g < design.graderCount(); g++) {
            List<Integer> questions = design.questionsOfGrader(g);
            if (questions.size() != 1) continue;
            int q = questions.get(0);
            if (design.gradersOfQuestion(q).size() > 1) continue;

            boolean anyComparable = false;
            for (int other = 0; other < design.graderCount(); other++) {
                if (other != g && underExchangeability[g][other]) {
                    anyComparable = true;
                    break;
                }
            }
            if (anyComparable) continue;

            out.add(Diagnostic.error(
                    Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                    "This assistant marked only " + design.questionLabels().get(q)
                    + ", and nobody else marked it. Their severity and that question's difficulty are "
                    + "only ever observed added together, so a harsh assistant and a hard question "
                    + "cannot be told apart.",
                    List.of(design.graderLabels().get(g), design.questionLabels().get(q)),
                    "Have a second assistant mark some of the scripts for "
                    + design.questionLabels().get(q) + ", or rotate this question's marker across "
                    + "blocks of roll numbers next time."));
        }
        return out;
    }

    /** Counts units that carry two marks, since those are what link assistants outright. */
    private static List<Diagnostic> doubleMarkingDiagnostics(MarkingDesign design) {
        Set<Long> seen = new HashSet<>();
        Set<Long> doubled = new HashSet<>();
        for (DesignCell c : design.cells()) {
            long key = ((long) c.assessment() << 42) ^ ((long) c.student() << 21) ^ c.question();
            if (!seen.add(key)) {
                doubled.add(key);
            }
        }
        if (doubled.isEmpty()) {
            return List.of(Diagnostic.warning(
                    Diagnostic.Code.EH005_NO_DOUBLE_MARKING,
                    "No script was marked by more than one assistant, so every comparison between "
                    + "assistants rests on shared students or shared questions alone.",
                    List.of(),
                    "A small set of double-marked scripts is the most direct way to make assistants "
                    + "comparable without relying on an assumption."));
        }
        return List.of(Diagnostic.note(
                Diagnostic.Code.EH008_DOUBLE_MARK_FOUND,
                doubled.size() + " response(s) were marked by more than one assistant. These are what "
                + "put the assistants who share them on one scale without any assumption at all.",
                List.of()));
    }
}
