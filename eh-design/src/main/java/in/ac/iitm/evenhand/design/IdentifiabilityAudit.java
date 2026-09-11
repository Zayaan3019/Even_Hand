package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.AllocationEvidence;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Diagnostic;
import in.ac.iitm.evenhand.core.Exposure;
import in.ac.iitm.evenhand.core.IdentifiabilityCertificate;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.numerics.ExactRowSpace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <h2>Two questions, not one</h2>
 *
 * <p>The audit asks whether severity could have moved anyone ({@link ExposureAudit})
 * before it asks whether severity can be measured. Severity harms a student only
 * through <em>differential exposure</em>: if every student's marks of record came from
 * the same assistants in the same proportions, a harsh assistant is indistinguishable
 * from a harder question and moves nobody relative to anybody. So a severity that
 * cannot be estimated may also be unable to do harm, and the diagnostics say so
 * rather than raising an error over nothing.
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
 * <ul>
 *   <li><strong>Question-wise.</strong> Each assistant marks one question across the
 *       class. Their severity is only ever observed added to that question's
 *       difficulty. No assumption about students touches this, because no student was
 *       marked on that question by anyone else. The excess null dimension is one less
 *       than the number of assistants: not a single severity difference is estimable
 *       &mdash; and, because every student met the same marker on each question, none
 *       of it can have moved anyone.</li>
 *   <li><strong>Script-wise.</strong> Each assistant marks a whole pile and nobody
 *       else touches it. Their severity is absorbed into the abilities of the students
 *       in that pile. The rank deficiency is exactly the same size, but it <em>is</em>
 *       rescuable, by assuming the piles were alike in ability. That is what random
 *       allocation buys.</li>
 * </ul>
 *
 * <p>The audit separates them by asking the same estimability question a second time
 * against a design whose student columns have been collapsed into one intercept
 * ({@link DesignMatrix#withStudentsCollapsed()}). Collapsing the students <em>is</em>
 * the exchangeability assumption, and it is also the fixed-effect part of the
 * random-ability model the estimator fits. If a contrast becomes estimable once it is
 * made, the confound was with ability and randomisation is the remedy; if it does not,
 * the confound is with question difficulty and only a second marker on the same
 * question will do.
 *
 * <p>A random split and a fixed roll-number split produce <em>identical</em> designs
 * of identical rank. The audit cannot tell them apart and does not pretend to; it
 * reports the {@link AllocationEvidence} that ingest computed from the real roll
 * numbers instead.
 *
 * <h2>Why exactly, and why twice</h2>
 *
 * <p>Rank is computed over the integers by {@link ExactRowSpace}, so the verdict is a
 * fact about the arrangement, not a consequence of a floating-point threshold. The
 * tests recompute every scenario's null dimension from the eigenvalues of the
 * information matrix and require agreement, and the certificate issued here is
 * re-verified wherever it is used. The established tool in this area, Facets, uses a
 * graph-joining heuristic derived from Weeks and Williams (1964) whose own manual
 * notes that "there are exotic forms of connectedness which Facets may falsely report
 * as disconnected"; computing the rank itself is what avoids that class of error.
 */
public final class IdentifiabilityAudit {

    private IdentifiabilityAudit() {
    }

    /** Audits with every question weighted equally. Pass weights when maxima are known. */
    public static AuditVerdict audit(MarkingDesign design) {
        return audit(design, ExposureAudit.unitWeights(design));
    }

    /**
     * @param questionWeights marks each question is worth times its assessment's weight
     *                        in the course grade; they decide exposure, never estimability
     */
    public static AuditVerdict audit(MarkingDesign design, double[] questionWeights) {
        List<Diagnostic> diagnostics = new ArrayList<>(doubleMarkingDiagnostics(design));
        double[][] shares = ExposureAudit.shares(design, questionWeights);

        DesignMatrix full = new DesignMatrix(design);
        ExactRowSpace fullSpace = rowSpaceOf(full, design);

        int excess = Math.max(0, fullSpace.nullity() - IdentifiabilityCertificate.NOMINAL_NULLITY);
        IdentifiabilityCertificate certificate = new IdentifiabilityCertificate(
                fullSpace.rank(), full.columns(), excess, design.fingerprint(), fullSpace.nullSpace());

        if (design.graderCount() < 2) {
            List<Set<Integer>> everyone = design.graderCount() == 1 ? List.of(Set.of(0)) : List.of();
            diagnostics.add(Diagnostic.error(
                    Diagnostic.Code.EH001_SINGLE_MARKER,
                    "All marking was done by one person, so there is no assistant-to-assistant "
                    + "difference for this analysis to be about.",
                    design.graderLabels(),
                    "No repair applies. Severity is only meaningful when marking is divided."));
            return new AuditVerdict.NotEstimable(certificate, diagnostics,
                    ExposureAudit.assess(shares, everyone, everyone),
                    "only one person marked this assessment");
        }

        DesignMatrix collapsed = full.withStudentsCollapsed();
        ExactRowSpace collapsedSpace = rowSpaceOf(collapsed, design);

        boolean[][] fromDesign = comparability(full, fullSpace, design.graderCount());
        boolean[][] underExchangeability = comparability(collapsed, collapsedSpace, design.graderCount());

        List<Set<Integer>> designClasses = classes(fromDesign, design.graderCount());
        List<Set<Integer>> exchangeabilityClasses = classes(underExchangeability, design.graderCount());
        Exposure exposure = ExposureAudit.assess(shares, designClasses, exchangeabilityClasses);

        if (designClasses.size() == 1) {
            if (excess == 0) {
                return new AuditVerdict.Estimable(certificate, diagnostics, exposure);
            }
            // Every severity difference is estimable, yet something else is not: groups of
            // students who were never set a question in common. Their abilities share a scale
            // only if the groups were alike, and saying "estimable" outright would hide that.
            diagnostics.add(Diagnostic.warning(
                    Diagnostic.Code.EH009_RESTS_ON_EXCHANGEABILITY,
                    "Every assistant can be placed on one scale, but some groups of students were never "
                    + "marked on a question in common, so their abilities share a scale only if those "
                    + "groups were alike to begin with.",
                    List.of(),
                    "Setting every group at least one common question removes the assumption."));
            return new AuditVerdict.EstimableUnderExchangeability(certificate, diagnostics, exposure,
                    "that groups of students who were set different questions were alike in ability",
                    describeAllocation(design));
        }

        diagnostics.addAll(nestingDiagnostics(design, underExchangeability));
        String allocationEvidence = describeAllocation(design);

        if (exchangeabilityClasses.size() == 1) {
            diagnostics.add(Diagnostic.warning(
                    Diagnostic.Code.EH009_RESTS_ON_EXCHANGEABILITY,
                    "No script was marked twice, so an assistant's severity is only separable from "
                    + "the ability of the students in their pile if those piles are taken to have "
                    + "been alike to begin with. " + allocationEvidence,
                    design.graderLabels(),
                    "Marking a small number of scripts a second time would remove the assumption entirely."));
            return new AuditVerdict.EstimableUnderExchangeability(certificate, diagnostics, exposure,
                    "that the piles of scripts given to each assistant were alike in ability",
                    allocationEvidence);
        }

        List<List<Integer>> groups = exchangeabilityClasses.stream()
                .map(c -> c.stream().sorted().toList())
                .sorted(Comparator.comparing((List<Integer> g) -> g.get(0)))
                .toList();

        if (groups.stream().noneMatch(g -> g.size() >= 2)) {
            diagnostics.add(noComparablePair(design, exposure));
            return new AuditVerdict.NotEstimable(certificate, diagnostics, exposure,
                    "no two assistants share enough marking to be compared");
        }

        List<String> restingOnAssumption = new ArrayList<>();
        for (List<Integer> group : groups) {
            if (group.size() >= 2 && !allComparable(fromDesign, group)) {
                group.forEach(g -> restingOnAssumption.add(design.graderLabels().get(g)));
            }
        }
        if (!restingOnAssumption.isEmpty()) {
            diagnostics.add(Diagnostic.warning(
                    Diagnostic.Code.EH009_RESTS_ON_EXCHANGEABILITY,
                    "Within their groups, these assistants can be compared only if their piles of scripts "
                    + "are taken to have been alike in ability. " + allocationEvidence,
                    restingOnAssumption,
                    "Marking a small number of scripts a second time would remove the assumption."));
        }
        String rendered = groups.stream()
                .map(g -> g.stream().map(i -> design.graderLabels().get(i))
                        .collect(Collectors.joining(", ", "[", "]")))
                .collect(Collectors.joining(", "));
        diagnostics.add(Diagnostic.warning(
                Diagnostic.Code.EH002_DISCONNECTED_GRADERS,
                "The assistants fall into " + groups.size() + " groups with no chain of shared marking "
                + "between them: " + rendered + ". Severities can be compared within a group, never "
                + "across groups.",
                design.graderLabels(),
                "Connecting " + groups.size() + " groups needs at least " + (groups.size() - 1)
                + " shared unit(s): each a script marked by assistants from two different groups."));
        return new AuditVerdict.PartiallyEstimable(certificate, diagnostics, exposure, groups);
    }

    /** The verdict-level explanation when no two assistants can be compared at all. */
    private static Diagnostic noComparablePair(MarkingDesign design, Exposure exposure) {
        if (exposure.isNone()) {
            return Diagnostic.note(
                    Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                    "No two assistants can be placed on the same scale: each one's severity is only ever "
                    + "seen together with the difficulty of the question they marked. That also means it "
                    + "cannot have moved anyone, because every student's marks of record came from the "
                    + "same assistants in the same proportions.",
                    design.graderLabels(),
                    "Only if you want per-assistant feedback: have a second assistant mark a few scripts on "
                    + "each question. Rotating markers also works, but only in a form that leaves every "
                    + "student with the same mix of assistants, or it creates the harm it measures.");
        }
        return Diagnostic.error(
                Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                "No two assistants can be placed on the same scale. Each one's severity is only ever seen "
                + "together with the difficulty of the question they marked, so only the sum of the two is "
                + "estimable, and students met different mixes of assistants, so it may have moved some of "
                + "them.",
                design.graderLabels(),
                "Have a second assistant mark a subset of scripts on each question. Where students met "
                + "different assistants on the same question, giving every student the same mix removes "
                + "the harm outright.");
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

    private static boolean allComparable(boolean[][] comparable, List<Integer> group) {
        for (int i = 0; i < group.size(); i++) {
            for (int j = i + 1; j < group.size(); j++) {
                if (!comparable[group.get(i)][group.get(j)]) return false;
            }
        }
        return true;
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
     * What the allocation looks like, as ingest read it from the real roll numbers.
     *
     * <p>Whether the piles were alike in ability is not knowable without the marks and
     * is not claimed here. Whether they were dealt in roll-number order <em>is</em>
     * knowable, but only from the roll numbers, which never reach this module; ingest
     * reduces them to {@link AllocationEvidence}. The student indices here are in
     * pseudonym order and say nothing about roll numbers, so without that evidence the
     * audit says it does not know rather than reading meaning into them.
     */
    private static String describeAllocation(MarkingDesign design) {
        return design.allocationEvidence()
                .map(AllocationEvidence::describe)
                .orElse("How the piles were dealt is not known, because the allocation arrived without "
                        + "roll numbers to judge it by.");
    }

    /**
     * Reports assistants whose severity is pinned to a single question's difficulty,
     * and says whether that can have harmed anyone.
     */
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

            String grader = design.graderLabels().get(g);
            String question = design.questionLabels().get(q);
            if (everyStudentsMarkOfRecordOn(design, q)) {
                out.add(Diagnostic.note(
                        Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                        "This assistant alone marked " + question + ", so their severity cannot be separated "
                        + "from " + question + "'s difficulty. It also cannot have moved anyone: every "
                        + "student's " + question + " was marked by this assistant.",
                        List.of(grader, question),
                        "Only if you want per-assistant feedback: have a second assistant mark ten or so "
                        + question + " scripts."));
            } else {
                out.add(Diagnostic.error(
                        Diagnostic.Code.EH003_SEVERITY_CONFOUNDED_WITH_QUESTION,
                        "This assistant marked only " + question + ", and nobody else marked it. Their "
                        + "severity and that question's difficulty are only ever observed added together, so "
                        + "a harsh assistant and a hard question cannot be told apart, and not every student "
                        + "answered " + question + ", so it may have moved some of them.",
                        List.of(grader, question),
                        "Have a second assistant mark some of the scripts for " + question + "."));
            }
        }
        return out;
    }

    /** Whether every student in the design has a mark of record on this question. */
    private static boolean everyStudentsMarkOfRecordOn(MarkingDesign design, int question) {
        Set<Integer> everyone = new HashSet<>();
        Set<Integer> marked = new HashSet<>();
        for (DesignCell c : design.cells()) {
            everyone.add(c.student());
            if (c.question() == question && c.ofRecord()) marked.add(c.student());
        }
        return marked.equals(everyone);
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
