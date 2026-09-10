package in.ac.iitm.evenhand.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything about a course that the platform must not assume.
 *
 * <p>The platform is meant for any IIT Madras instructor who divides marking among
 * assistants, and courses here differ in ways that change what is knowable: class
 * sizes from twenty to several hundred, marking alone or in teams of ten, binary,
 * partial-credit or ordinal scores, and four different families of cut-off rule. So
 * none of it is compiled in. Each field is inferred where it can be, asked for once
 * where it cannot, and carries its {@link Provenance} either way.
 *
 * <p>The instructor may override any detected field; the override is recorded as
 * {@link Provenance#DECLARED} and appears in the report footer, so the reader can see
 * which of the analysis's inputs were measured and which were asserted.
 *
 * @param courseLabel      how the course is identified in output; never a roll number
 * @param arrangement      how the marking was divided, per the profiler
 * @param scoreType        binary, partial-credit or ordinal
 * @param cutoffPolicy     how marks become letters
 * @param assessmentWeights weight per assessment index; R.19.1 makes a course grade a
 *                         weighted composite, and its parts may have been marked
 *                         under different arrangements
 * @param maxScorePerQuestion maximum available on each question index
 * @param positionRecorded whether the marking order is known, which decides whether
 *                         drift can be tested at all
 */
public record CourseProfile(Provenanced<String> courseLabel,
                            Provenanced<Arrangement> arrangement,
                            Provenanced<ScoreType> scoreType,
                            Provenanced<CutoffPolicy> cutoffPolicy,
                            Provenanced<Map<Integer, Double>> assessmentWeights,
                            Provenanced<Map<Integer, Integer>> maxScorePerQuestion,
                            Provenanced<Boolean> positionRecorded) {

    /**
     * The profile the platform starts from before anything has been detected or
     * asked. Every field is DEFAULTED, and the intake screen exists to replace them.
     */
    public static CourseProfile blank(String courseLabel) {
        return new CourseProfile(
                Provenanced.declared(courseLabel, "instructor"),
                Provenanced.defaulted(Arrangement.UNKNOWN),
                Provenanced.defaulted(ScoreType.PARTIAL_CREDIT),
                Provenanced.defaulted(CutoffPolicy.iitmConventionalRelative()),
                Provenanced.defaulted(Map.of(0, 1.0)),
                Provenanced.defaulted(Map.of()),
                Provenanced.defaulted(Boolean.FALSE));
    }

    /**
     * The lines that go at the foot of every report. An instructor reading a
     * conclusion should be able to see, without asking us, which inputs the tool
     * measured and which it was told.
     */
    public List<String> provenanceFootnotes() {
        List<String> out = new ArrayList<>();
        out.add(courseLabel.describe("course"));
        out.add(arrangement.describe("marking arrangement"));
        out.add(scoreType.describe("score type"));
        out.add(cutoffPolicy.describe("cut-off rule"));
        out.add(positionRecorded.describe("marking order recorded"));
        return out;
    }

    /** True when any input that shaped the analysis was never confirmed by anyone. */
    public boolean hasUnconfirmedDefaults() {
        return List.of(arrangement, scoreType, cutoffPolicy, positionRecorded).stream()
                .anyMatch(p -> p.provenance() == Provenance.DEFAULTED);
    }

    public CourseProfile withArrangement(Provenanced<Arrangement> a) {
        return new CourseProfile(courseLabel, a, scoreType, cutoffPolicy,
                assessmentWeights, maxScorePerQuestion, positionRecorded);
    }

    public CourseProfile withScoreType(Provenanced<ScoreType> s) {
        return new CourseProfile(courseLabel, arrangement, s, cutoffPolicy,
                assessmentWeights, maxScorePerQuestion, positionRecorded);
    }

    public CourseProfile withCutoffPolicy(Provenanced<CutoffPolicy> c) {
        return new CourseProfile(courseLabel, arrangement, scoreType, c,
                assessmentWeights, maxScorePerQuestion, positionRecorded);
    }

    public CourseProfile withPositionRecorded(Provenanced<Boolean> p) {
        return new CourseProfile(courseLabel, arrangement, scoreType, cutoffPolicy,
                assessmentWeights, maxScorePerQuestion, p);
    }
}
