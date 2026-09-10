package in.ac.iitm.evenhand.core;

import java.util.List;
import java.util.Map;

/**
 * How this course turns marks into letters.
 *
 * <p>This is configuration, and it has to be, because the Institute does not
 * prescribe it. The B.Tech Ordinances fix the grade set and points (R.21.1), say the
 * marks themselves are absolute (R.19.2), and place the awarding of letters with the
 * Class Committee meeting held within seven days of the last end-semester exam
 * (R.22.1, R.4.4(d)) &mdash; but nowhere define a mapping. The familiar
 * mean-and-sigma bands and the fixed top share are departmental convention. Public
 * accounts of them disagree with each other, which is itself the argument for asking
 * the instructor rather than assuming.
 *
 * @param kind        which family of rule
 * @param anchorGrade the grade whose band starts at the anchor point, for SIGMA_BANDS
 * @param bandOffsets band lower edges in standard deviations from the mean, for SIGMA_BANDS
 * @param topFraction the fixed share receiving the top grade, for TOP_FRACTION
 * @param absolute    mark thresholds per grade, for ABSOLUTE
 * @param sBandPolicy how a fixed top share interacts with the sigma bands
 */
public record CutoffPolicy(Kind kind,
                           LetterGrade anchorGrade,
                           Map<LetterGrade, Double> bandOffsets,
                           double topFraction,
                           Map<LetterGrade, Double> absolute,
                           SBandPolicy sBandPolicy) {

    public enum Kind {
        /** Boundaries at the class mean plus multiples of the class standard deviation. */
        SIGMA_BANDS,
        /** A fixed share of the class receives each grade. */
        TOP_FRACTION,
        /** Fixed mark thresholds, unaffected by the cohort. */
        ABSOLUTE,
        /** Boundaries the instructor draws by hand after looking at the distribution. */
        INSTRUCTOR_BANDS
    }

    /**
     * When both a sigma rule and a fixed top share are in play, which governs S.
     * The distinction matters: a normal cohort puts about 2% beyond two standard
     * deviations, while a top-10% rule admits five times that. If the fixed share
     * binds, the number of S grades is fixed before a single script is marked, and
     * every S awarded to a leniently marked student is one taken from someone else.
     */
    public enum SBandPolicy {
        /** The fixed share decides; the sigma boundary is ignored for S. */
        TOP_FRACTION_BINDS,
        /** The sigma boundary decides; the share is not applied. */
        SIGMA_BINDS,
        /** Whichever admits more students. */
        MAX_OF_BOTH
    }

    public CutoffPolicy {
        bandOffsets = bandOffsets == null ? Map.of() : Map.copyOf(bandOffsets);
        absolute    = absolute    == null ? Map.of() : Map.copyOf(absolute);
    }

    /**
     * The convention this project's stakeholder described, recorded so it can be
     * confirmed or corrected rather than assumed: B at the class mean, A one standard
     * deviation above, S at two, C at one below, D at two, E at three, with the top
     * tenth of the class awarded S.
     *
     * <p>Offered as a starting point for the intake screen, never as a default that
     * runs unseen. It is labelled {@link Provenance#DEFAULTED} until an instructor
     * confirms it.
     */
    public static CutoffPolicy iitmConventionalRelative() {
        return new CutoffPolicy(
                Kind.SIGMA_BANDS,
                LetterGrade.B,
                Map.of(LetterGrade.S,  2.0,
                       LetterGrade.A,  1.0,
                       LetterGrade.B,  0.0,
                       LetterGrade.C, -1.0,
                       LetterGrade.D, -2.0,
                       LetterGrade.E, -3.0),
                0.10,
                Map.of(),
                SBandPolicy.TOP_FRACTION_BINDS);
    }

    public static CutoffPolicy absolute(Map<LetterGrade, Double> thresholds) {
        return new CutoffPolicy(Kind.ABSOLUTE, null, Map.of(), Double.NaN,
                thresholds, SBandPolicy.SIGMA_BINDS);
    }

    /** Grades this policy can award, best first. */
    public List<LetterGrade> awardable() {
        return List.of(LetterGrade.awardable());
    }
}
