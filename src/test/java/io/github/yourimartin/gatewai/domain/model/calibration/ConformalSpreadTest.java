package io.github.yourimartin.gatewai.domain.model.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the spread ADR 0019 quotes. The expected values were computed independently
 * (exact binomial sums in Python, 2026-11-27) from the same Beta(n + 1 − k, k) law,
 * so a regression here means the ADR's table no longer describes the code.
 */
class ConformalSpreadTest {

  @ParameterizedTest(name = "n={0}: P(rate > 0.15) = {1}, P(rate > 0.20) = {2}")
  @CsvSource({
      "20, 0.175558, 0.069175",
      "50, 0.112105, 0.018496",
      "93, 0.049633, 0.002314",
      "200, 0.014880, 0.000045"
  })
  @DisplayName("the exceedance table of ADR 0019, at alpha = 0.10")
  void reproducesTheAdrTable(int n, double over15, double over20) {
    assertEquals(over15, ConformalSpread.exceedance(n, 0.10, 0.15), 1e-6);
    assertEquals(over20, ConformalSpread.exceedance(n, 0.10, 0.20), 1e-6);
  }

  @ParameterizedTest(name = "n={0}, alpha={1}: p90 = {2}")
  @CsvSource({
      "3, 0.25, 0.535841",
      "50, 0.10, 0.153548",
      "93, 0.10, 0.135971"
  })
  void theUpperQuantileInvertsTheExceedance(int n, double alpha, double p90) {
    double quantile = ConformalSpread.upperQuantile(n, alpha, 0.90);

    assertEquals(p90, quantile, 1e-6);
    assertEquals(0.10, ConformalSpread.exceedance(n, alpha, quantile), 1e-9);
  }

  @Test
  @DisplayName("more negatives, a tighter fit: the p90 shrinks toward alpha")
  void theSpreadShrinksWithTheSample() {
    double small = ConformalSpread.upperQuantile(20, 0.10, 0.90);
    double large = ConformalSpread.upperQuantile(2000, 0.10, 0.90);

    assertTrue(small > large, small + " vs " + large);
    assertTrue(large > 0.10 && large < 0.12, String.valueOf(large));
  }

  @Test
  void staysFiniteAtSampleSizesNoNaiveProductSurvives() {
    // 0.9^10000 underflows a double: the sum must run in log space.
    double exceedance = ConformalSpread.exceedance(10_000, 0.10, 0.105);

    assertTrue(exceedance > 0 && exceedance < 0.1, String.valueOf(exceedance));
  }

  @Test
  void refusesWhatTheQuantileCannotSupport() {
    // alpha = 0.10 needs 9 negatives.
    assertThrows(IllegalArgumentException.class,
        () -> ConformalSpread.exceedance(8, 0.10, 0.15));
    assertThrows(IllegalArgumentException.class,
        () -> ConformalSpread.upperQuantile(50, 0.10, 1.0));
    assertThrows(IllegalArgumentException.class,
        () -> ConformalSpread.upperQuantile(50, 0, 0.9));
  }

  @Test
  void theEndsOfTheRateAreCertain() {
    assertEquals(1, ConformalSpread.exceedance(50, 0.10, 0));
    assertEquals(0, ConformalSpread.exceedance(50, 0.10, 1));
  }
}
