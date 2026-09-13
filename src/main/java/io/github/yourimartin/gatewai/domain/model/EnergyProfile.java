package io.github.yourimartin.gatewai.domain.model;

/**
 * How much electricity one inference on a model draws, split by phase
 * (v3 lot C.4):
 *
 * <pre>
 *   kWh = prefill × promptTokens/1000
 *       + decode  × completionTokens/1000
 *       + fixed
 * </pre>
 *
 * <p><b>Why a split.</b> Prefill is compute-bound and processes the whole prompt in
 * parallel; decode is memory-bandwidth-bound and strictly sequential, one token at a
 * time over the same weights. Per token the two differ by more than an order of
 * magnitude, so the single scalar this replaces gave a 10k-token prompt with a
 * 50-token answer and its mirror image the same figure. {@code RequestLog} has
 * always persisted the two token counts separately; only the model threw them away.
 *
 * <p>{@code fixed} exists for vendor figures published <em>per prompt</em> rather
 * than per token — Google's median-Gemini-prompt number is exactly that shape.
 *
 * <p>{@code includesDatacenterOverhead} says whether the coefficients already carry
 * facility overhead (cooling, conversion). A parametric GPU-level estimate does not,
 * so PUE applies to it; a full-stack vendor figure does, and multiplying it by PUE
 * again would double-count. Pure domain, zero framework dependencies.
 *
 * @param prefillKwhPer1kPromptTokens     kWh per 1000 prompt tokens
 * @param decodeKwhPer1kCompletionTokens  kWh per 1000 completion tokens
 * @param fixedKwhPerRequest              kWh charged once per inference
 * @param source                          provenance of these coefficients;
 *                                        {@code null} is derived — all zero means
 *                                        {@link EnergySource#NOT_ACCOUNTED},
 *                                        anything else {@link EnergySource#MODELLED}
 * @param includesDatacenterOverhead      whether PUE is already inside the numbers
 */
public record EnergyProfile(
    double prefillKwhPer1kPromptTokens,
    double decodeKwhPer1kCompletionTokens,
    double fixedKwhPerRequest,
    EnergySource source,
    boolean includesDatacenterOverhead
) {

  private static final double TOKENS_PER_UNIT = 1000.0;

  /** Energy excluded from scope: nothing measured, nothing estimated, nothing booked. */
  public static final EnergyProfile NOT_ACCOUNTED =
      new EnergyProfile(0.0, 0.0, 0.0, EnergySource.NOT_ACCOUNTED, false);

  public EnergyProfile {
    requireNonNegative(prefillKwhPer1kPromptTokens, "prefill");
    requireNonNegative(decodeKwhPer1kCompletionTokens, "decode");
    requireNonNegative(fixedKwhPerRequest, "fixed");
    boolean allZero = prefillKwhPer1kPromptTokens == 0.0
        && decodeKwhPer1kCompletionTokens == 0.0
        && fixedKwhPerRequest == 0.0;
    if (source == null) {
      source = allZero ? EnergySource.NOT_ACCOUNTED : EnergySource.MODELLED;
    }
    if (source == EnergySource.NOT_ACCOUNTED && !allZero) {
      throw new IllegalArgumentException(
          "energy-source=not-accounted but a non-zero coefficient was declared "
              + "(prefill=" + prefillKwhPer1kPromptTokens
              + ", decode=" + decodeKwhPer1kCompletionTokens
              + ", fixed=" + fixedKwhPerRequest + "): an unaccounted model is booked "
              + "at zero. Drop the coefficients or declare their source.");
    }
  }

  /**
   * Electrical energy for one inference, before any PUE is applied.
   *
   * @param promptTokens     prompt (prefill) tokens; negatives count as zero
   * @param completionTokens completion (decode) tokens; negatives count as zero
   * @return energy in kWh, {@code 0} for an unaccounted model
   */
  public double kwh(long promptTokens, long completionTokens) {
    return prefillKwhPer1kPromptTokens * (Math.max(0L, promptTokens) / TOKENS_PER_UNIT)
        + decodeKwhPer1kCompletionTokens
            * (Math.max(0L, completionTokens) / TOKENS_PER_UNIT)
        + fixedKwhPerRequest;
  }

  /** Whether emissions from this model are included in the reported totals. */
  public boolean accounted() {
    return source.accounted();
  }

  private static void requireNonNegative(double value, String name) {
    if (value < 0.0 || Double.isNaN(value)) {
      throw new IllegalArgumentException(
          "energy coefficient '" + name + "' must be zero or positive, was " + value);
    }
  }
}
