package io.github.yourimartin.gatewai.domain.model.calibration;

/**
 * How far the wrong-answer rate one fit actually delivers can sit from {@code α}
 * (v4 D.3, ADR 0019). Modelled, never measured.
 *
 * <p>A split-conformal guarantee holds on average over calibration draws. For one
 * draw of {@code n} continuous scores, the share of future negatives the fitted
 * threshold admits follows {@code Beta(n + 1 − k, k)}, with
 * {@code k = ⌈(n + 1)(1 − α)⌉} the rank {@link ConformalQuantile} takes (Vovk,
 * 2012; Angelopoulos &amp; Bates, arXiv:2107.07511). Both parameters are integers,
 * so the CDF is a binomial tail, which this class sums in log space to stay exact
 * at the sample sizes a calibration sees.
 */
public final class ConformalSpread {

  private static final int BISECTION_STEPS = 60;

  private ConformalSpread() {
  }

  /**
   * The probability that one fit on {@code n} negatives at {@code alpha} admits
   * more than {@code rate} of future negatives.
   *
   * @throws IllegalArgumentException when {@code n} cannot support {@code alpha}
   */
  public static double exceedance(int n, double alpha, double rate) {
    int[] shape = shape(n, alpha);
    if (rate <= 0) {
      return 1;
    }
    if (rate >= 1) {
      return 0;
    }
    return 1 - betaCdf(rate, shape[0], shape[1]);
  }

  /**
   * The {@code p}-quantile of the admitted share: with probability {@code p}, one
   * fit on {@code n} negatives at {@code alpha} admits at most this share of
   * future negatives.
   *
   * @throws IllegalArgumentException when {@code n} cannot support {@code alpha},
   *                                  or {@code p} is outside (0, 1)
   */
  public static double upperQuantile(int n, double alpha, double p) {
    if (!(p > 0 && p < 1)) {
      throw new IllegalArgumentException("p must be in (0,1), was " + p);
    }
    int[] shape = shape(n, alpha);
    double low = 0;
    double high = 1;
    for (int i = 0; i < BISECTION_STEPS; i++) {
      double mid = (low + high) / 2;
      if (betaCdf(mid, shape[0], shape[1]) < p) {
        low = mid;
      } else {
        high = mid;
      }
    }
    return (low + high) / 2;
  }

  /** {a, b} of the Beta law, or a refusal when the quantile does not exist. */
  private static int[] shape(int n, double alpha) {
    if (alpha <= 0 || alpha >= 1) {
      throw new IllegalArgumentException("alpha must be in (0,1), was " + alpha);
    }
    int k = (int) Math.ceil((n + 1) * (1 - alpha));
    if (n <= 0 || k > n) {
      throw new IllegalArgumentException(
          n + " negatives cannot support alpha=" + alpha + ": no quantile exists");
    }
    return new int[] {n + 1 - k, k};
  }

  /**
   * The CDF of {@code Beta(a, b)} at {@code x} for integer {@code a, b}:
   * {@code P(Binomial(a + b − 1, x) ≥ a)}.
   */
  private static double betaCdf(double x, int a, int b) {
    int trials = a + b - 1;
    double logX = Math.log(x);
    double logNotX = Math.log1p(-x);
    double max = Double.NEGATIVE_INFINITY;
    double[] logTerms = new double[trials - a + 1];
    // log C(trials, j), built up incrementally from log C(trials, a).
    double logChoose = logChoose(trials, a);
    for (int j = a; j <= trials; j++) {
      if (j > a) {
        logChoose += Math.log(trials - j + 1) - Math.log(j);
      }
      double term = logChoose + j * logX + (trials - j) * logNotX;
      logTerms[j - a] = term;
      max = Math.max(max, term);
    }
    double sum = 0;
    for (double term : logTerms) {
      sum += Math.exp(term - max);
    }
    return Math.min(1, Math.exp(max + Math.log(sum)));
  }

  private static double logChoose(int n, int k) {
    double log = 0;
    for (int i = 1; i <= k; i++) {
      log += Math.log(n - k + i) - Math.log(i);
    }
    return log;
  }
}
