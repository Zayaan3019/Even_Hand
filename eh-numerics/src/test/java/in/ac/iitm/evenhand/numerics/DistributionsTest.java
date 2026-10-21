package in.ac.iitm.evenhand.numerics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DistributionsTest {

    @Test
    @DisplayName("log-gamma matches factorials and Gamma(1/2)")
    void logGamma() {
        assertThat(Distributions.logGamma(1)).isCloseTo(0, within(1e-12));
        assertThat(Distributions.logGamma(6)).isCloseTo(Math.log(120), within(1e-11));
        assertThat(Distributions.logGamma(0.5)).isCloseTo(0.5 * Math.log(Math.PI), within(1e-12));
        assertThat(Distributions.logGamma(101)).isCloseTo(363.73937555556347, within(1e-9));
    }

    @Test
    @DisplayName("chi-square tails match tables")
    void chiSquare() {
        assertThat(Distributions.chiSquareSurvival(3.841458820694124, 1)).isCloseTo(0.05, within(1e-9));
        assertThat(Distributions.chiSquareSurvival(11.070497693516351, 5)).isCloseTo(0.05, within(1e-9));
        assertThat(Distributions.chiSquareSurvival(2.0, 2)).isCloseTo(Math.exp(-1), within(1e-12));
        assertThat(Distributions.chiSquareSurvival(50, 10)).isCloseTo(2.6690834e-7, within(1e-12));
    }

    @Test
    @DisplayName("binomial tails: exact small cases and symmetry")
    void binomial() {
        assertThat(Distributions.binomialUpperTail(10, 10, 0.5)).isCloseTo(1 / 1024.0, within(1e-14));
        assertThat(Distributions.binomialUpperTail(8, 10, 0.5)).isCloseTo(56 / 1024.0, within(1e-13));
        assertThat(Distributions.binomialUpperTail(0, 10, 0.3)).isEqualTo(1.0);
        assertThat(Distributions.binomialUpperTail(11, 10, 0.3)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("normal CDF at familiar points")
    void normal() {
        assertThat(Distributions.normalCdf(0)).isCloseTo(0.5, within(1e-7));
        assertThat(Distributions.normalCdf(1.959963985)).isCloseTo(0.975, within(1e-7));
        assertThat(Distributions.normalCdf(-3)).isCloseTo(0.0013498980316301, within(1e-8));
    }
}
