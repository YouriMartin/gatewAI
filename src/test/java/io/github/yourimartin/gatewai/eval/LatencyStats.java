package io.github.yourimartin.gatewai.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * Percentiles of a measured duration, in milliseconds (v2 batch 5).
 *
 * @param p50     median
 * @param p95     95th percentile
 * @param samples how many measurements it was computed from
 */
record LatencyStats(double p50, double p95, int samples) {

  static final LatencyStats NONE = new LatencyStats(0, 0, 0);

  /** Nearest-rank percentiles: no interpolation, no assumption about the shape. */
  static LatencyStats of(List<Double> measurements) {
    if (measurements.isEmpty()) {
      return NONE;
    }
    List<Double> sorted = new ArrayList<>(measurements);
    sorted.sort(null);
    return new LatencyStats(percentile(sorted, 0.50), percentile(sorted, 0.95), sorted.size());
  }

  private static double percentile(List<Double> sorted, double fraction) {
    int rank = (int) Math.ceil(fraction * sorted.size());
    return sorted.get(Math.clamp(rank - 1, 0, sorted.size() - 1));
  }
}
