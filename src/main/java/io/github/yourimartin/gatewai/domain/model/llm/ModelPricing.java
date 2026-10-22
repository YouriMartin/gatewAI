package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * What one registry model costs, in the billing currency (v4 B.6).
 *
 * <p>A {@link PricingBasis#SPLIT} price follows the providers' own tariffs:
 * prompt tokens at the input rate, completion tokens at the output rate, and the
 * prompt tokens the provider served from its prompt cache at the cached-input
 * rate. When the provider does not report cached tokens, or the model declares
 * no cached rate, those tokens are priced at the input rate — the request log
 * keeps which (a {@code null} cached count means not reported).
 *
 * @param basis            how the price is expressed
 * @param inputPer1m       prompt tokens, per million ({@code SPLIT})
 * @param outputPer1m      completion tokens, per million ({@code SPLIT})
 * @param cachedInputPer1m prompt tokens read from the provider's cache, per
 *                         million; {@code null} prices them at {@code inputPer1m}
 * @param blendedPer1k     every token alike, per thousand ({@code BLENDED})
 */
public record ModelPricing(
    PricingBasis basis,
    double inputPer1m,
    double outputPer1m,
    Double cachedInputPer1m,
    double blendedPer1k
) {

  /** No price declared. */
  public static final ModelPricing NONE = new ModelPricing(PricingBasis.NONE, 0, 0, null, 0);

  private static final double PER_MILLION = 1_000_000.0;
  private static final double PER_THOUSAND = 1_000.0;

  public ModelPricing {
    if (basis == null) {
      throw new IllegalArgumentException("A price needs a basis");
    }
    requireNonNegative(inputPer1m, "input");
    requireNonNegative(outputPer1m, "output");
    if (cachedInputPer1m != null) {
      requireNonNegative(cachedInputPer1m, "cached input");
    }
    requireNonNegative(blendedPer1k, "blended");
  }

  /** Per-million rates for input, output and, optionally, cached input. */
  public static ModelPricing split(double inputPer1m, double outputPer1m,
                                   Double cachedInputPer1m) {
    return new ModelPricing(PricingBasis.SPLIT, inputPer1m, outputPer1m, cachedInputPer1m, 0);
  }

  /** The deprecated single rate per 1000 tokens. */
  public static ModelPricing blended(double per1k) {
    return new ModelPricing(PricingBasis.BLENDED, 0, 0, null, per1k);
  }

  /** What {@code usage} costs at this price; zero for {@link PricingBasis#NONE}. */
  public double cost(TokenUsage usage) {
    return switch (basis) {
      case NONE -> 0.0;
      case BLENDED -> usage.totalTokens() / PER_THOUSAND * blendedPer1k;
      case SPLIT -> {
        int cached = usage.cachedPromptTokens() == null ? 0
            : Math.clamp(usage.cachedPromptTokens(), 0, Math.max(0, usage.promptTokens()));
        double cachedRate = cachedInputPer1m == null ? inputPer1m : cachedInputPer1m;
        yield ((usage.promptTokens() - cached) * inputPer1m
            + cached * cachedRate
            + usage.completionTokens() * outputPer1m) / PER_MILLION;
      }
    };
  }

  private static void requireNonNegative(double rate, String name) {
    if (rate < 0 || Double.isNaN(rate)) {
      throw new IllegalArgumentException("The " + name + " price must be zero or more, got "
          + rate);
    }
  }
}
