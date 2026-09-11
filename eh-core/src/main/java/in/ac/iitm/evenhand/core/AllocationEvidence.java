package in.ac.iitm.evenhand.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the roll numbers reveal about how the piles were dealt, reduced to counts.
 *
 * <p>A random split and a roll-number split are the same design of the same rank, so
 * the audit cannot tell them apart; only the roll numbers can, and whether the split
 * followed them is the thing most likely to make "the piles were alike" false, since
 * roll-number order groups students by department, admission year and programme.
 *
 * <p>Roll numbers exist only inside ingest. So this is computed there, while they are
 * still in memory, and handed on as statistics: how many unbroken runs of consecutive
 * roll numbers each assistant's pile falls into, and how closely piles follow the
 * department code at the front of a roll number. Nothing here says where any
 * individual student stands in the roll list, so it cannot be used to reverse a
 * pseudonym.
 *
 * <p>Only marks of record define a pile; a handful of scripts second-marked for a
 * check are not how the class was divided.
 *
 * @param piles                  one entry per assistant with marks of record
 * @param classSize              students in the design
 * @param departmentCount        distinct department codes read from roll numbers; 0 when none
 * @param departmentPurity       across the piles that were assessed, the share of a pile
 *                               from its own most common department, weighted by pile size;
 *                               NaN when it cannot be computed
 * @param largestDepartmentShare share of the class from its most common department; NaN when unknown
 */
public record AllocationEvidence(List<Pile> piles,
                                 int classSize,
                                 int departmentCount,
                                 double departmentPurity,
                                 double largestDepartmentShare) {

    /**
     * @param grader index of the assistant
     * @param size   students in the pile
     * @param runs   unbroken runs of consecutive roll numbers the pile falls into
     */
    public record Pile(int grader, int size, int runs) {}

    public AllocationEvidence {
        piles = List.copyOf(piles);
    }

    /**
     * Computes the evidence from each student's place in roll-number order.
     *
     * @param rollRank   {@code rollRank[s]} is student {@code s}'s position when the class
     *                   is sorted by roll number; used here and not retained
     * @param department {@code department[s]} is the department code of student {@code s}'s
     *                   roll number, or null where none could be read; not retained
     */
    public static AllocationEvidence fromRollOrder(List<DesignCell> cells, int studentCount, int graderCount,
                                                   int[] rollRank, String[] department) {
        if (rollRank.length != studentCount || department.length != studentCount) {
            throw new IllegalArgumentException("need one roll rank and one department entry per student");
        }
        List<TreeSet<Integer>> ranks = new ArrayList<>();
        List<Set<Integer>> members = new ArrayList<>();
        for (int g = 0; g < graderCount; g++) {
            ranks.add(new TreeSet<>());
            members.add(new HashSet<>());
        }
        for (DesignCell c : cells) {
            if (!c.ofRecord()) continue;
            ranks.get(c.grader()).add(rollRank[c.student()]);
            members.get(c.grader()).add(c.student());
        }

        List<Pile> piles = new ArrayList<>();
        for (int g = 0; g < graderCount; g++) {
            if (ranks.get(g).isEmpty()) continue;
            int runs = 1;
            Integer previous = null;
            for (int r : ranks.get(g)) {
                if (previous != null && r != previous + 1) runs++;
                previous = r;
            }
            piles.add(new Pile(g, ranks.get(g).size(), runs));
        }

        Map<String, Integer> classCounts = new HashMap<>();
        int known = 0;
        for (String d : department) {
            if (d == null) continue;
            classCounts.merge(d, 1, Integer::sum);
            known++;
        }
        double largestShare = known == 0 ? Double.NaN
                : classCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0) / (double) known;

        double purity = Double.NaN;
        if (known > 0) {
            int dominantTotal = 0;
            int sizeTotal = 0;
            for (Pile p : piles) {
                if (!isAssessed(p, studentCount)) continue;
                Map<String, Integer> pileCounts = new HashMap<>();
                int pileKnown = 0;
                for (int s : members.get(p.grader())) {
                    if (department[s] == null) continue;
                    pileCounts.merge(department[s], 1, Integer::sum);
                    pileKnown++;
                }
                dominantTotal += pileCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                sizeTotal += pileKnown;
            }
            if (sizeTotal > 0) purity = dominantTotal / (double) sizeTotal;
        }
        return new AllocationEvidence(piles, studentCount, classCounts.size(), purity, largestShare);
    }

    /**
     * Contiguity only carries information about how piles were dealt when there were
     * piles: an assistant who marked the whole class has a trivially contiguous "pile",
     * and one who marked a single script has no shape at all.
     */
    private static boolean isAssessed(Pile p, int classSize) {
        return p.size() >= 2 && p.size() < 0.9 * classSize;
    }

    /** A pile dealt at random breaks into roughly as many runs as it has members. */
    private static boolean isContiguous(Pile p) {
        return p.runs() <= Math.max(2, p.size() / 10);
    }

    /** Whether the piles track the department code, which roll-number order also groups by. */
    public boolean followsDepartments() {
        return departmentCount >= 2
                && !Double.isNaN(departmentPurity) && departmentPurity >= 0.9
                && largestDepartmentShare <= 0.8;
    }

    /** The sentence the audit puts in front of the instructor. */
    public String describe() {
        List<Pile> assessed = piles.stream().filter(p -> isAssessed(p, classSize)).toList();
        if (assessed.isEmpty()) {
            return "There is not enough marking per assistant to say how the piles were formed.";
        }
        long contiguous = assessed.stream().filter(AllocationEvidence::isContiguous).count();
        String out;
        if (contiguous == assessed.size()) {
            out = "Every assistant's pile is a contiguous block of roll numbers, so the split was made by "
                    + "roll number rather than at random, and the assumption that the piles were alike in "
                    + "ability is the one most worth doubting.";
        } else if (contiguous == 0) {
            out = "Assistants' piles are interleaved across roll numbers, which is consistent with a random deal.";
        } else {
            out = contiguous + " of " + assessed.size() + " assistants' piles are contiguous blocks of roll "
                    + "numbers, so the split was at least partly made by roll number.";
        }
        if (followsDepartments()) {
            out += " The piles also follow the department code in the roll number, so an assistant's "
                    + "severity cannot be told apart from the department of the students they marked.";
        }
        return out;
    }
}
