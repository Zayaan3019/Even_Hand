package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.Arrangement;
import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Provenanced;
import in.ac.iitm.evenhand.core.Response;
import in.ac.iitm.evenhand.core.ScoreType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Works out what kind of course this is, from the data rather than from being told.
 *
 * <p>The platform is meant for any instructor who divides marking among assistants, and
 * an instructor cannot be expected to describe their own arrangement in the vocabulary
 * of a measurement model. So everything that can be inferred is inferred, and what the
 * profiler concludes arrives as a {@link Provenanced} value carrying the evidence for
 * it, pre-filled on the intake screen for the instructor to confirm or overrule.
 *
 * <p>Nothing downstream branches on the arrangement label. It exists so the report can
 * describe the course in the instructor's own terms; the audit reaches its verdict from
 * the incidence structure regardless of what the arrangement is called.
 *
 * <h2>How the arrangement is decided</h2>
 *
 * <p>Two coverage figures separate the cases, and they are chosen because they stay
 * meaningful when the arrangement is untidy. For each assistant, what fraction of the
 * question paper did they see, and what fraction of the class?
 *
 * <ul>
 *   <li><strong>Script-wise:</strong> high question coverage, low student coverage. Each
 *       assistant marks a whole script for a subset of the class.</li>
 *   <li><strong>Question-wise:</strong> low question coverage, high student coverage.
 *       Each assistant marks one question across everybody.</li>
 *   <li><strong>Crossed:</strong> both high, with nobody marking twice. This is rotation,
 *       and it is the only free repair for a confounded design.</li>
 *   <li><strong>Mixed:</strong> anything else.</li>
 * </ul>
 *
 * <p>Coverage is used rather than a count of questions per assistant because a
 * question-wise course with a handful of scripts second-marked has every assistant
 * touching two questions, which a naive count reads as script-wise. Coverage still sees
 * two out of five as low, and gets it right.
 */
public final class DesignProfiler {

    /** Above this fraction, an assistant is treated as having seen "most" of something. */
    private static final double HIGH_COVERAGE = 0.8;

    private DesignProfiler() {
    }

    /**
     * What the profiler concluded, and what it saw.
     *
     * @param arrangement       how the marking was divided
     * @param scoreType         binary or partial credit; ordinal cannot be told apart
     *                          from partial credit by looking, and is DECLARED only
     * @param positionRecorded  whether drift can be tested at all for this course
     * @param doubleMarkedUnits responses carrying more than one mark
     * @param notes             plain-language observations for the intake screen
     */
    public record ProfileReport(Provenanced<Arrangement> arrangement,
                                Provenanced<ScoreType> scoreType,
                                Provenanced<Boolean> positionRecorded,
                                int doubleMarkedUnits,
                                List<String> notes) {

        public ProfileReport {
            notes = List.copyOf(notes);
        }
    }

    /**
     * Profiles from the marking arrangement alone, with no marks.
     *
     * <p>This is the mode that matters in the first week: an instructor can be told what
     * kind of arrangement he has, and what it will support, before he shares a single
     * score. The score type cannot be inferred without scores, so it is left defaulted
     * and flagged rather than guessed.
     */
    public static ProfileReport profile(MarkingDesign design) {
        return profile(design, List.of());
    }

    public static ProfileReport profile(MarkingDesign design, List<Response> responses) {
        List<String> notes = new ArrayList<>();

        Provenanced<Arrangement> arrangement = classifyArrangement(design, notes);
        Provenanced<ScoreType> scoreType = classifyScoreType(responses, notes);
        int doubleMarked = countDoubleMarkedUnits(design);

        boolean anyPosition = design.anyPositionRecorded();
        Provenanced<Boolean> positionRecorded = Provenanced.detected(anyPosition,
                anyPosition
                        ? "at least one row records the script's place in its assistant's sequence"
                        : "no row records the order scripts were marked in");
        if (!anyPosition) {
            notes.add("Whether marks drift as an assistant works through a pile cannot be tested "
                    + "for this course, because the marking order was not recorded.");
        }

        if (doubleMarked > 0) {
            notes.add(doubleMarked + " response(s) were marked by more than one assistant. These "
                    + "link the assistants who share them onto one scale without relying on any "
                    + "assumption about who received which scripts.");
        } else {
            notes.add("No response was marked twice.");
        }

        return new ProfileReport(arrangement, scoreType, positionRecorded, doubleMarked, notes);
    }

    private static Provenanced<Arrangement> classifyArrangement(MarkingDesign design, List<String> notes) {
        if (design.graderCount() < 2) {
            return Provenanced.detected(Arrangement.SINGLE_MARKER,
                    "one assistant marked everything");
        }
        if (design.size() == 0) {
            return Provenanced.detected(Arrangement.UNKNOWN, "no marking recorded");
        }

        double questionCoverage = meanCoverage(design, true);
        double studentCoverage = meanCoverage(design, false);
        boolean highQuestions = questionCoverage >= HIGH_COVERAGE;
        boolean highStudents = studentCoverage >= HIGH_COVERAGE;

        String evidence = String.format(Locale.ROOT,
                "each assistant saw %.0f%% of the questions and %.0f%% of the class",
                100 * questionCoverage, 100 * studentCoverage);

        Arrangement arrangement;
        if (highQuestions && !highStudents) {
            arrangement = Arrangement.SCRIPT_WISE;
            notes.add("Each assistant marked whole scripts for part of the class. An assistant's "
                    + "severity is therefore entangled with the ability of the students they "
                    + "happened to receive.");
        } else if (!highQuestions && highStudents) {
            arrangement = Arrangement.QUESTION_WISE;
            notes.add("Each assistant marked a small part of the paper across most of the class. "
                    + "An assistant's severity is therefore entangled with the difficulty of the "
                    + "question they marked, which only a second marker on that question resolves.");
        } else if (highQuestions) {
            arrangement = Arrangement.CROSSED;
            notes.add("Assistants were rotated across both questions and students. This is the "
                    + "arrangement that identifies every severity without anyone marking twice.");
        } else {
            arrangement = Arrangement.MIXED;
            notes.add("Neither pattern dominates; the audit reports which assistants this "
                    + "arrangement can and cannot compare.");
        }
        return Provenanced.detected(arrangement, evidence);
    }

    /** Mean fraction of questions (or students) that an assistant was shown. */
    private static double meanCoverage(MarkingDesign design, boolean overQuestions) {
        int total = overQuestions ? design.questionCount() : design.studentCount();
        if (total == 0) {
            return 0.0;
        }
        List<Set<Integer>> seen = new ArrayList<>();
        for (int g = 0; g < design.graderCount(); g++) {
            seen.add(new HashSet<>());
        }
        for (DesignCell c : design.cells()) {
            seen.get(c.grader()).add(overQuestions ? c.question() : c.student());
        }
        double sum = 0.0;
        int active = 0;
        for (Set<Integer> s : seen) {
            if (s.isEmpty()) {
                continue;
            }
            active++;
            sum += s.size() / (double) total;
        }
        return active == 0 ? 0.0 : sum / active;
    }

    private static Provenanced<ScoreType> classifyScoreType(List<Response> responses, List<String> notes) {
        if (responses.isEmpty()) {
            notes.add("The score type cannot be determined without the marks; it will be settled "
                    + "when they are loaded.");
            return Provenanced.defaulted(ScoreType.PARTIAL_CREDIT);
        }

        int maxObserved = 0;
        TreeSet<Integer> distinct = new TreeSet<>();
        for (Response r : responses) {
            maxObserved = Math.max(maxObserved, r.maxScore());
            distinct.add(r.score());
        }

        if (maxObserved <= 1) {
            return Provenanced.detected(ScoreType.BINARY,
                    "every question is scored out of 1, and only " + distinct + " were awarded");
        }

        // Ordinal categories and part-marks look identical in a score column: both are
        // ordered integers. Telling them apart needs to know whether the steps are equal
        // in marks, which only the instructor knows, so we report partial credit and say
        // that the alternative is his to declare.
        notes.add("Scores run to " + maxObserved + " with " + distinct.size() + " distinct values "
                + "observed, read as part-marks. If these are ordered categories rather than marks "
                + "-- a rubric band, say -- tell the tool so, because it changes how the steps "
                + "between them are modelled.");
        return Provenanced.detected(ScoreType.PARTIAL_CREDIT,
                "scores range over " + distinct.first() + ".." + maxObserved
                + " with intermediate values awarded");
    }

    private static int countDoubleMarkedUnits(MarkingDesign design) {
        Set<Long> seen = new HashSet<>();
        Set<Long> doubled = new HashSet<>();
        for (DesignCell c : design.cells()) {
            long key = ((long) c.assessment() << 42) ^ ((long) c.student() << 21) ^ c.question();
            if (!seen.add(key)) {
                doubled.add(key);
            }
        }
        return doubled.size();
    }
}
