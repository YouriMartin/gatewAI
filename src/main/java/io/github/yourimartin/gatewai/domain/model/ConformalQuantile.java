package io.github.yourimartin.gatewai.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The split-conformal quantile (v2 batch 3).
 *
 * <p>Given non-conformity scores measured on a labelled calibration sample, it
 * returns the value {@code q̂} such that a new, exchangeable observation scores
 * at or below it with probability at least {@code 1 − α}:
 *
 * <pre>{@code q̂ = the ⌈(n+1)(1−α)⌉-th smallest score}</pre>
 *
 * <p>The {@code n+1} is the whole point and the reason this is not
 * {@code percentile(scores, 1-alpha)}. It is the finite-sample correction that
 * turns an empirical percentile into a distribution-free guarantee: the new
 * observation is treated as the {@code (n+1)}-th member of an exchangeable
 * sample, so its rank among the others is uniform. Drop it and the guarantee
 * holds only asymptotically — which, on 200 hand-labelled cases, means it does
 * not hold.
 *
 * <p>When {@code ⌈(n+1)(1−α)⌉ > n} the sample is simply too small to promise
 * {@code 1 − α} at all, and this returns {@linkplain OptionalDouble#empty()
 * nothing} rather than the largest score it happens to hold. Silently
 * substituting the maximum is how a calibration ends up claiming a guarantee it
 * cannot support.
 */
public final class ConformalQuantile {

  private ConformalQuantile() {
  }

  /**
   * Returns {@code q̂} for {@code scores} at risk level {@code alpha}, or empty
   * when the sample is too small to support that level.
   *
   * @param scores non-conformity scores, one per calibration case; not modified
   * @param alpha  risk level in {@code (0, 1)} — the share of future cases the
   *               guarantee is allowed to miss
   */
  public static OptionalDouble of(List<Double> scores, double alpha) {
    if (alpha <= 0 || alpha >= 1) {
      throw new IllegalArgumentException("alpha must be in (0,1), was " + alpha);
    }
    if (scores == null || scores.isEmpty()) {
      return OptionalDouble.empty();
    }

    int n = scores.size();
    int rank = (int) Math.ceil((n + 1) * (1 - alpha));
    if (rank > n) {
      return OptionalDouble.empty();
    }

    List<Double> sorted = new ArrayList<>(scores);
    sorted.sort(null);
    return OptionalDouble.of(sorted.get(rank - 1));
  }

  /**
   * The smallest calibration sample that can support {@code alpha}, i.e. the
   * smallest {@code n} with {@code ⌈(n+1)(1−α)⌉ ≤ n}.
   *
   * <p>Worth surfacing in an error message: at α = 0.01 no fewer than 99 cases
   * can ever justify the claim, whatever the scores look like.
   */
  public static int minimumSampleSize(double alpha) {
    if (alpha <= 0 || alpha >= 1) {
      throw new IllegalArgumentException("alpha must be in (0,1), was " + alpha);
    }
    int n = 1;
    while (Math.ceil((n + 1) * (1 - alpha)) > n) {
      n++;
    }
    return n;
  }
}
