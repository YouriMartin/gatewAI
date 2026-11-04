package io.github.yourimartin.gatewai.domain.model.shadow;

/**
 * The Wilson score interval of a binomial proportion (v4 D.2): unlike the normal
 * approximation it stays inside [0, 1] and does not collapse to a point at 0 or
 * n successes, which is exactly where a small shadow sample lives.
 *
 * @param low  lower bound
 * @param high upper bound
 */
public record WilsonInterval(double low, double high) {

  /** z for a two-sided 95 % interval. */
  public static final double Z_95 = 1.959963984540054;

  /**
   * The 95 % interval of {@code successes} out of {@code trials}.
   *
   * @throws IllegalArgumentException for no trials, or counts out of range: an
   *                                  interval of nothing is not [0, 1], it is absent
   */
  public static WilsonInterval of(long successes, long trials) {
    return of(successes, trials, Z_95);
  }

  /** As {@link #of(long, long)}, at an explicit {@code z}. */
  public static WilsonInterval of(long successes, long trials, double z) {
    if (trials <= 0 || successes < 0 || successes > trials) {
      throw new IllegalArgumentException(
          "need 0 <= successes <= trials and trials > 0, got " + successes + "/" + trials);
    }
    double n = trials;
    double p = successes / n;
    double z2 = z * z;
    double denominator = 1 + z2 / n;
    double centre = (p + z2 / (2 * n)) / denominator;
    double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denominator;
    return new WilsonInterval(Math.max(0, centre - half), Math.min(1, centre + half));
  }
}
