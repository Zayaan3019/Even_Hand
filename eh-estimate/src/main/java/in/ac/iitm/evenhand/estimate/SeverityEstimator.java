package in.ac.iitm.evenhand.estimate;

import in.ac.iitm.evenhand.core.AnchoredDesign;
import in.ac.iitm.evenhand.core.AuditVerdict;
import in.ac.iitm.evenhand.core.Facet;
import in.ac.iitm.evenhand.core.FacetEstimates;
import in.ac.iitm.evenhand.core.FittedModel;
import in.ac.iitm.evenhand.core.MarkingDesign;
import in.ac.iitm.evenhand.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Stage 3: how many marks did each assistant add or take away?
 *
 * <p>Three steps, each simple enough to explain at the whiteboard:
 * <ol>
 *   <li><b>Fit</b> the additive model by backfitting ({@link Backfit}).</li>
 *   <li><b>Bootstrap</b>: resample the students with replacement and refit, many times.
 *       The spread of an assistant's severity across resamples is its uncertainty. Because
 *       whole students are resampled, it includes the chance that one assistant's pile
 *       happened to hold stronger students, which is the main uncertainty in
 *       script-wise marking.</li>
 *   <li><b>No-effect test</b>: simulate the marking again on the <em>same</em> allocation
 *       with every assistant equally severe, refit, and see how often chance alone
 *       produces a spread as large as the one observed. Shuffling the assistant labels
 *       instead would turn a question-wise design into a crossed one and test the
 *       wrong thing.</li>
 * </ol>
 *
 * <p>The only way in is an {@link AnchoredDesign}, which cannot be built for a design
 * the audit refused. So an unaudited fit cannot be written.
 */
public final class SeverityEstimator {

    private SeverityEstimator() {
    }

    public static FittedModel fit(AnchoredDesign anchored, List<Response> responses, Settings settings) {
        MarkingDesign design = anchored.design();
        List<List<Integer>> groups = comparableGroups(anchored.verdict(), design.graderCount());
        Observations obs = Observations.from(responses,
                design.studentCount(), design.questionCount(), design.graderCount());
        settings = forVerdict(settings, anchored.verdict());

        Backfit.Result fit = Backfit.fit(obs, groups, settings);
        double[][] draws = bootstrap(obs, groups, settings);
        FittedModel.NoEffectTest noEffect = noEffectTest(obs, fit, groups, settings);

        return new FittedModel(
                anchored.verdict(),
                anchored.anchoring(),
                fit.mean(),
                facet(Facet.STUDENT, design.studentLabels(), fit.ability(), null, countBy(obs.student(), obs.students())),
                facet(Facet.QUESTION, design.questionLabels(), fit.easiness(), null, countBy(obs.question(), obs.questions())),
                facet(Facet.GRADER, design.graderLabels(), fit.severity(), draws, countBy(obs.grader(), obs.graders())),
                groups,
                fit.residualSd(),
                draws,
                noEffect,
                new FittedModel.FitDiagnostics(fit.iterations(), fit.maxChange(), fit.converged()));
    }

    /**
     * Student shrinkage <em>is</em> the exchangeability assumption. When the marking itself
     * links every assistant, nothing needs assuming and students are not shrunk at all.
     * When the audit says the comparison rests on the piles having been alike, shrinking
     * each student towards the class average is how that assumption enters the fit, and
     * its strength is the trained constant.
     */
    static Settings forVerdict(Settings settings, AuditVerdict verdict) {
        return verdict instanceof AuditVerdict.EstimableUnderExchangeability
                ? settings
                : settings.withShrinkage(0, settings.graderShrinkage());
    }

    /** Which assistants share a scale, according to the audit. */
    static List<List<Integer>> comparableGroups(AuditVerdict verdict, int graders) {
        if (verdict instanceof AuditVerdict.PartiallyEstimable p) {
            return p.groups();
        }
        return List.of(IntStream.range(0, graders).boxed().toList());
    }

    /** Resample whole students and refit; one row of severities per resample. */
    static double[][] bootstrap(Observations obs, List<List<Integer>> groups, Settings settings) {
        int[][] rowsOf = rowsByStudent(obs);
        double[][] draws = new double[settings.bootstrapDraws()][];
        // Each draw has its own seed, so the result does not depend on thread scheduling.
        IntStream.range(0, settings.bootstrapDraws()).parallel().forEach(b -> {
            SplittableRandom rng = new SplittableRandom(settings.seed() * 1_000_003L + b);
            draws[b] = Backfit.fit(resampleStudents(obs, rowsOf, rng), groups, settings).severity();
        });
        return draws;
    }

    static Observations resampleStudents(Observations obs, int[][] rowsOf, SplittableRandom rng) {
        int total = 0;
        int[] picked = new int[obs.students()];
        for (int k = 0; k < picked.length; k++) {
            picked[k] = rng.nextInt(picked.length);
            total += rowsOf[picked[k]].length;
        }
        int[] s = new int[total], q = new int[total], g = new int[total];
        double[] y = new double[total], w = new double[total];
        int at = 0;
        for (int k = 0; k < picked.length; k++) {
            for (int row : rowsOf[picked[k]]) {
                s[at] = k;   // a student drawn twice counts as two students
                q[at] = obs.question()[row];
                g[at] = obs.grader()[row];
                y[at] = obs.y()[row];
                w[at] = obs.w()[row];
                at++;
            }
        }
        return new Observations(s, q, g, y, w, obs.students(), obs.questions(), obs.graders());
    }

    /**
     * Simulates marking in which every assistant is equally severe, on the same allocation,
     * with the students dealt to it afresh and noise resampled from the same question's
     * residuals, and counts how often the refitted spread reaches the observed one.
     */
    static FittedModel.NoEffectTest noEffectTest(Observations obs, Backfit.Result fit,
                                                  List<List<Integer>> groups, Settings settings) {
        double observed = Backfit.spread(fit.severity(), groups);
        int m = settings.nullSimulations();
        if (m == 0) {
            return new FittedModel.NoEffectTest(observed, Double.NaN, 0);
        }
        double[][] residualsOf = residualsByQuestion(obs, fit);
        Settings quick = settings.withDraws(0, 0);
        int[] atLeast = new int[m];
        IntStream.range(0, m).parallel().forEach(k -> {
            SplittableRandom rng = new SplittableRandom(~settings.seed() * 7_919L + k);
            double[] ability = dealtAtRandom(fit.ability(), rng);
            double[] y = new double[obs.size()];
            for (int i = 0; i < y.length; i++) {
                double[] pool = residualsOf[obs.question()[i]];
                double noise = pool.length == 0 ? 0 : pool[rng.nextInt(pool.length)];
                y[i] = fit.mean() + ability[obs.student()[i]] + fit.easiness()[obs.question()[i]] + noise;
            }
            double spread = Backfit.spread(Backfit.fit(obs.withMarks(y), groups, quick).severity(), groups);
            atLeast[k] = spread >= observed ? 1 : 0;
        });
        int count = 0;
        for (int x : atLeast) count += x;
        return new FittedModel.NoEffectTest(observed, (1.0 + count) / (1.0 + m), m);
    }

    /**
     * The students' abilities shuffled among them: the scripts dealt to the piles afresh.
     * Without this the no-effect worlds would all share the real deal, never see one
     * assistant happen to get the stronger pile, and call chance a severity. (The benchmark
     * caught exactly that: four false alarms in four tutorial-group courses.)
     */
    static double[] dealtAtRandom(double[] ability, SplittableRandom rng) {
        double[] out = ability.clone();
        for (int i = out.length - 1; i > 0; i--) {
            int k = rng.nextInt(i + 1);
            double t = out[i];
            out[i] = out[k];
            out[k] = t;
        }
        return out;
    }

    private static double[][] residualsByQuestion(Observations obs, Backfit.Result fit) {
        List<List<Double>> lists = new ArrayList<>();
        for (int q = 0; q < obs.questions(); q++) lists.add(new ArrayList<>());
        for (int i = 0; i < obs.size(); i++) {
            lists.get(obs.question()[i]).add(obs.y()[i] - fit.fitted(obs, i));
        }
        double[][] out = new double[obs.questions()][];
        for (int q = 0; q < out.length; q++) {
            out[q] = lists.get(q).stream().mapToDouble(Double::doubleValue).toArray();
        }
        return out;
    }

    private static int[][] rowsByStudent(Observations obs) {
        int[] counts = countBy(obs.student(), obs.students());
        int[][] rows = new int[obs.students()][];
        for (int k = 0; k < rows.length; k++) rows[k] = new int[counts[k]];
        int[] fill = new int[obs.students()];
        for (int i = 0; i < obs.size(); i++) {
            int st = obs.student()[i];
            rows[st][fill[st]++] = i;
        }
        return rows;
    }

    private static int[] countBy(int[] index, int size) {
        int[] counts = new int[size];
        for (int x : index) counts[x]++;
        return counts;
    }

    private static FacetEstimates facet(Facet facet, List<String> labels, double[] estimates,
                                        double[][] draws, int[] counts) {
        double[] se = new double[estimates.length];
        if (draws != null && draws.length > 1) {
            for (int j = 0; j < se.length; j++) {
                double mean = 0;
                for (double[] d : draws) mean += d[j];
                mean /= draws.length;
                double ss = 0;
                for (double[] d : draws) ss += (d[j] - mean) * (d[j] - mean);
                se[j] = Math.sqrt(ss / (draws.length - 1));
            }
        }
        return new FacetEstimates(facet, labels, estimates.clone(), se, counts, new boolean[estimates.length]);
    }
}
