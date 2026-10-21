package in.ac.iitm.evenhand.numerics;

/**
 * The few tail probabilities the causal checks need: chi-square for heterogeneity across
 * questions, the binomial for sign tests and their Rosenbaum bounds, the normal for intervals.
 * Accurate to about 1e-12 where the tests use them; checked against tabulated values.
 */
public final class Distributions {

    private Distributions() {
    }

    /** ln Gamma(x) for x > 0 (Lanczos, g = 7, n = 9). */
    public static double logGamma(double x) {
        if (!(x > 0)) throw new IllegalArgumentException("logGamma needs x > 0");
        if (x < 0.5) return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
        double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
                1.5056327351493116e-7};
        double z = x - 1;
        double a = c[0];
        double t = z + 7.5;
        for (int i = 1; i < 9; i++) a += c[i] / (z + i);
        return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Regularised upper incomplete gamma Q(a, x) = Gamma(a, x) / Gamma(a). */
    public static double gammaQ(double a, double x) {
        if (x < 0 || a <= 0) throw new IllegalArgumentException("gammaQ needs a > 0, x >= 0");
        if (x == 0) return 1;
        if (x < a + 1) {
            double sum = 1 / a, term = sum;
            for (int n = 1; n < 1000; n++) {
                term *= x / (a + n);
                sum += term;
                if (Math.abs(term) < Math.abs(sum) * 1e-16) break;
            }
            return 1 - sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
        }
        double tiny = 1e-300;
        double b = x + 1 - a, c = 1 / tiny, d = 1 / b, h = d;
        for (int i = 1; i < 1000; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < tiny) d = tiny;
            c = b + an / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < 1e-16) break;
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    /** P(X >= x) for a chi-square variable with {@code df} degrees of freedom. */
    public static double chiSquareSurvival(double x, int df) {
        if (df < 1) throw new IllegalArgumentException("df must be >= 1");
        if (x <= 0) return 1;
        return gammaQ(df / 2.0, x / 2.0);
    }

    /** P(X >= k) for X ~ Binomial(n, p). */
    public static double binomialUpperTail(int k, int n, double p) {
        if (k <= 0) return 1;
        if (k > n) return 0;
        if (p <= 0) return 0;
        if (p >= 1) return 1;
        double lp = Math.log(p), lq = Math.log1p(-p);
        double lognFact = logGamma(n + 1.0);
        double sum = 0;
        for (int i = k; i <= n; i++) {
            sum += Math.exp(lognFact - logGamma(i + 1.0) - logGamma(n - i + 1.0) + i * lp + (n - i) * lq);
        }
        return Math.min(1, sum);
    }

    /** Standard normal CDF. */
    public static double normalCdf(double z) {
        return 0.5 * erfc(-z / Math.sqrt(2));
    }

    /** Complementary error function, relative error below 1.2e-7 everywhere (Numerical Recipes erfcc). */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }
}
