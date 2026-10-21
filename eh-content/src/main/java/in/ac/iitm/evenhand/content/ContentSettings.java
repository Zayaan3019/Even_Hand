package in.ac.iitm.evenhand.content;

/**
 * The content analysis's knobs. The matching ones were trained on the training split of the
 * benchmark (synthetic IITM courses whose answers and true severities are known) and checked
 * once on the held-out split and on a real double-graded dataset; see docs/VALIDATION.md.
 *
 * @param neighbours         answers by other assistants each answer is compared with, at most
 * @param caliper            the least cosine similarity at which two answers count as saying
 *                           the same thing, for a language-model embedding
 * @param lexicalCaliper     the same for the lexical fallback, whose cosines run lower
 * @param minLinkPairs       matched pairs two assistants need before they are compared directly
 * @param minSignal          the least share of within-pile mark variation the answers must
 *                           explain (cross-validated) before content is trusted to compare TAs
 * @param folds              cross-fitting folds for the content model
 * @param bootstrapDraws     multiplier-bootstrap draws for every interval and the p-value
 * @param severityShrinkage  marks of matched evidence at which an assistant's severity is halved
 * @param evidenceSimilarity cosine above which a pair is shown as "the same answer"
 * @param evidenceGap        least gap, in percentage points of the question, worth showing
 * @param outlierZ           robust z-score past which a mark is out of line with similar answers
 * @param outlierGap         and the least gap, in percentage points, for that to matter
 * @param seed               fixes every random draw, so a report reproduces exactly
 */
public record ContentSettings(int neighbours,
                              double caliper,
                              double lexicalCaliper,
                              int minLinkPairs,
                              double minSignal,
                              int folds,
                              int bootstrapDraws,
                              double severityShrinkage,
                              double evidenceSimilarity,
                              double evidenceGap,
                              double outlierZ,
                              double outlierGap,
                              long seed) {

    public ContentSettings {
        if (neighbours < 1) throw new IllegalArgumentException("neighbours must be >= 1");
        if (folds < 2) throw new IllegalArgumentException("folds must be >= 2");
        if (bootstrapDraws < 0) throw new IllegalArgumentException("bootstrapDraws cannot be negative");
        if (!(caliper > -1 && caliper < 1) || !(lexicalCaliper > -1 && lexicalCaliper < 1)) {
            throw new IllegalArgumentException("calipers are cosines in (-1, 1)");
        }
    }

    /** The values trained on the benchmark's training split. */
    public static ContentSettings defaults() {
        return new ContentSettings(1, 0.70, 0.45, 10, 0.15, 5, 200, 10.0, 0.90, 20.0, 3.5, 25.0, 20261022L);
    }

    public ContentSettings withMatching(int k, double semanticCaliper, double lexical) {
        return new ContentSettings(k, semanticCaliper, lexical, minLinkPairs, minSignal, folds, bootstrapDraws,
                severityShrinkage, evidenceSimilarity, evidenceGap, outlierZ, outlierGap, seed);
    }

    public ContentSettings withShrinkage(double marks) {
        return new ContentSettings(neighbours, caliper, lexicalCaliper, minLinkPairs, minSignal, folds, bootstrapDraws,
                marks, evidenceSimilarity, evidenceGap, outlierZ, outlierGap, seed);
    }

    public ContentSettings withDraws(int draws) {
        return new ContentSettings(neighbours, caliper, lexicalCaliper, minLinkPairs, minSignal, folds, draws,
                severityShrinkage, evidenceSimilarity, evidenceGap, outlierZ, outlierGap, seed);
    }

    public ContentSettings withSeed(long newSeed) {
        return new ContentSettings(neighbours, caliper, lexicalCaliper, minLinkPairs, minSignal, folds, bootstrapDraws,
                severityShrinkage, evidenceSimilarity, evidenceGap, outlierZ, outlierGap, newSeed);
    }
}
