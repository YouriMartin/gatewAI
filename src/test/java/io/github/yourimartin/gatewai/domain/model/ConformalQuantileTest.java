package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConformalQuantileTest {

  @Test
  @DisplayName("picks the ceil((n+1)(1-alpha))-th smallest score, not the percentile")
  void usesTheFiniteSampleRank() {
    // n = 9, alpha = 0.1 -> rank = ceil(10 * 0.9) = 9, the largest score.
    List<Double> scores = List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9);

    assertEquals(0.9, ConformalQuantile.of(scores, 0.10).orElseThrow(), 1e-12);
    // The plain 90th percentile would have landed lower; the n+1 correction is
    // exactly what buys the distribution-free guarantee on a small sample.
  }

  @Test
  void isInsensitiveToInputOrder() {
    List<Double> ascending = List.of(0.1, 0.2, 0.3, 0.4, 0.5);
    List<Double> shuffled = List.of(0.4, 0.1, 0.5, 0.3, 0.2);

    assertEquals(ConformalQuantile.of(ascending, 0.30).orElseThrow(),
        ConformalQuantile.of(shuffled, 0.30).orElseThrow());
  }

  @Test
  @DisplayName("returns nothing when the sample cannot support the level asked for")
  void refusesWhenTheSampleIsTooSmall() {
    // n = 5, alpha = 0.01 -> rank = ceil(6 * 0.99) = 6 > 5.
    assertEquals(OptionalDouble.empty(),
        ConformalQuantile.of(List.of(0.1, 0.2, 0.3, 0.4, 0.5), 0.01));
    assertEquals(OptionalDouble.empty(), ConformalQuantile.of(List.of(), 0.10));
  }

  @Test
  void minimumSampleSizeIsTheSmallestNThatWorks() {
    for (double alpha : new double[] {0.01, 0.05, 0.10, 0.20}) {
      int n = ConformalQuantile.minimumSampleSize(alpha);

      assertTrue(ConformalQuantile.of(scores(n), alpha).isPresent(),
          "n=" + n + " should support alpha=" + alpha);
      assertTrue(ConformalQuantile.of(scores(n - 1), alpha).isEmpty(),
          "n=" + (n - 1) + " should not support alpha=" + alpha);
    }
  }

  @Test
  void rejectsAlphaOutsideTheOpenUnitInterval() {
    assertThrows(IllegalArgumentException.class,
        () -> ConformalQuantile.of(List.of(0.1), 0.0));
    assertThrows(IllegalArgumentException.class,
        () -> ConformalQuantile.of(List.of(0.1), 1.0));
  }

  @Test
  @DisplayName("empirical coverage reaches 1-alpha on exchangeable synthetic data")
  void coversAtLeastOneMinusAlphaOnSyntheticData() {
    Random random = new Random(20260811L);
    double alpha = 0.10;
    int trials = 2000;
    int covered = 0;

    for (int trial = 0; trial < trials; trial++) {
      // Calibration and test draws come from the same distribution, which is
      // the exchangeability the guarantee rests on — and the assumption the
      // documentation has to keep flagging, because production traffic is not
      // obliged to honour it.
      List<Double> calibration = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        calibration.add(random.nextGaussian());
      }
      double qhat = ConformalQuantile.of(calibration, alpha).orElseThrow();
      if (random.nextGaussian() <= qhat) {
        covered++;
      }
    }

    double coverage = (double) covered / trials;
    // 1 - alpha = 0.90, and a 2000-draw standard error is about 0.7 points.
    assertTrue(coverage >= 0.88 && coverage <= 0.95,
        "empirical coverage " + coverage + " should sit at about " + (1 - alpha));
  }

  private static List<Double> scores(int n) {
    List<Double> scores = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      scores.add(i / (double) Math.max(1, n));
    }
    return scores;
  }
}
