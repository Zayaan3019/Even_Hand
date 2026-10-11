package in.ac.iitm.evenhand.estimate;

/**
 * The estimator's few knobs.
 *
 * <p>The two shrinkage constants were <em>trained</em>: chosen by grid search on the
 * training half of the IITM course benchmark, which knows the true severities, and
 * then checked once on the held-out half (see {@code docs/BENCHMARK.md} and
 * {@code eh-app}'s {@code Benchmark}). Each is measured in marks of evidence: a student
 * whose answers are worth {@code studentShrinkage} marks in total is pulled halfway
 * towards the class average.
 *
 * <p>Student shrinkage is applied only when the audit says the comparison rests on the
 * piles having been alike: it is how that assumption enters the fit (see
 * {@link SeverityEstimator#forVerdict}). Training chose 5.
 *
 * @param studentShrinkage marks of evidence at which a student's ability is halved
 * @param graderShrinkage  marks of evidence at which an assistant's severity is halved
 * @param bootstrapDraws   resamples of the students, for every interval
 * @param nullSimulations  no-effect worlds simulated for the significance test
 * @param seed             fixes every random draw, so a report reproduces exactly
 */
public record Settings(double studentShrinkage,
                       double graderShrinkage,
                       int bootstrapDraws,
                       int nullSimulations,
                       long seed,
                       int maxIterations,
                       double tolerance) {

    public Settings {
        if (studentShrinkage < 0 || graderShrinkage < 0) {
            throw new IllegalArgumentException("shrinkage cannot be negative");
        }
        if (bootstrapDraws < 0 || nullSimulations < 0) {
            throw new IllegalArgumentException("draw counts cannot be negative");
        }
    }

    /** The values trained on the benchmark's training split. */
    public static Settings defaults() {
        return new Settings(5.0, 50.0, 200, 99, 20261010L, 2000, 1e-5);
    }

    public Settings withShrinkage(double student, double grader) {
        return new Settings(student, grader, bootstrapDraws, nullSimulations, seed, maxIterations, tolerance);
    }

    public Settings withDraws(int bootstrap, int nulls) {
        return new Settings(studentShrinkage, graderShrinkage, bootstrap, nulls, seed, maxIterations, tolerance);
    }

    public Settings withSeed(long newSeed) {
        return new Settings(studentShrinkage, graderShrinkage, bootstrapDraws, nullSimulations, newSeed,
                maxIterations, tolerance);
    }
}
