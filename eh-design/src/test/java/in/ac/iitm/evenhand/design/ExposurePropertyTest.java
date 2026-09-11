package in.ac.iitm.evenhand.design;

import in.ac.iitm.evenhand.core.DesignCell;
import in.ac.iitm.evenhand.core.Exposure;
import in.ac.iitm.evenhand.core.MarkingDesign;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exposure claim as a property rather than a handful of examples.
 *
 * <p>Whatever the class size, the number of questions, their worth, and which assistant
 * got which question, one marker of record per question exposes nobody differently.
 * And handing a single student's answer to a different assistant always breaks that,
 * because it is exactly the kind of allocation in which severity can move one student
 * relative to the rest.
 */
class ExposurePropertyTest {

    @Property(tries = 150)
    void oneMarkerOfRecordPerQuestionExposesNobodyDifferently(
            @ForAll @IntRange(min = 2, max = 30) int students,
            @ForAll @IntRange(min = 1, max = 8) int questions,
            @ForAll @IntRange(min = 2, max = 5) int graders,
            @ForAll long seed) {
        Random rng = new Random(seed);
        int[] markerOf = new int[questions];
        double[] weights = new double[questions];
        for (int q = 0; q < questions; q++) {
            markerOf[q] = rng.nextInt(graders);
            weights[q] = 1 + rng.nextInt(20);
        }
        List<DesignCell> cells = new ArrayList<>();
        for (int s = 0; s < students; s++) {
            for (int q = 0; q < questions; q++) {
                cells.add(new DesignCell(s, q, markerOf[q]));
            }
        }

        Exposure balanced = IdentifiabilityAudit.audit(design(cells, students, questions, graders), weights)
                .exposure();
        assertThat(balanced.isNone()).isTrue();

        int student = rng.nextInt(students);
        int question = rng.nextInt(questions);
        int other = (markerOf[question] + 1 + rng.nextInt(graders - 1)) % graders;
        List<DesignCell> perturbed = new ArrayList<>(cells);
        perturbed.replaceAll(c -> c.student() == student && c.question() == question
                ? new DesignCell(student, question, other) : c);

        Exposure broken = IdentifiabilityAudit.audit(design(perturbed, students, questions, graders), weights)
                .exposure();
        assertThat(broken.isNone()).isFalse();
        assertThat(broken.maxSpread()).isGreaterThan(0.0);
    }

    private static MarkingDesign design(List<DesignCell> cells, int students, int questions, int graders) {
        return MarkingDesign.of(cells, labels("Student", students), labels("Q", questions),
                labels("Assistant ", graders));
    }

    private static List<String> labels(String prefix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add(prefix + i);
        return out;
    }
}
