package io.github.yourimartin.gatewai.domain.model.carbon;

import java.util.Currency;
import java.util.Locale;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

/**
 * Computes per-request {@link GreenMetrics} (Phase 4.3): cost, energy, emissions
 * and CO2 avoided versus a premium-by-default baseline.
 *
 * <p>Pure domain logic composing the {@link CarbonCalculator}. The "avoided" figure
 * is {@code emission(premium baseline) − emission(actual model)} for the same token
 * usage: it credits the carbon saved by routing a request to a cheaper/greener model
 * instead of the most capable one.
 *
 * <p>Each side is a {@link ModelSite} — a model <em>plus</em> the grid and the
 * datacenter it ran on (v3 lot C.3). The premium baseline is a counterfactual about
 * the premium provider's datacenter, so it is priced there, not at the grid of
 * whatever answered. When both sit behind the same provider the two sites are equal.
 */
public final class GreenAccountant {

  private final CarbonCalculator carbonCalculator;
  private final String currency;

  /**
   * @param currency the ISO 4217 code every configured price is in
   *                 ({@code gatewai.billing.currency}); an unknown code fails here,
   *                 at startup, rather than on the first report
   */
  public GreenAccountant(CarbonCalculator carbonCalculator, String currency) {
    this.carbonCalculator = carbonCalculator;
    this.currency = isoCurrency(currency);
  }

  /** The billing currency every metric this accountant produces is in. */
  public String currency() {
    return currency;
  }

  /**
   * Accounts for a single served request.
   *
   * <p>On a cache hit no inference happens: real cost, energy and emissions are
   * zero, and the whole premium-default call is credited as avoided. This is the
   * cache's headline saving, which would otherwise be invisible.
   *
   * <p>Both sides are priced by their own {@link ModelPricing} (v4 B.6), so the
   * avoided cost compares split prices whenever the registry declares them: an
   * input-heavy request is no longer credited at an average rate.
   *
   * @param used     the model that served the request and where it ran; its model
   *                 may be {@code null} (ignored on a cache hit)
   * @param baseline the premium-default model and where it would have run, or
   *                 {@code null}
   * @param usage    token counts as the provider reported them
   * @param cacheHit whether the response was served from cache
   * @return the metrics, never {@code null}; {@link GreenMetrics#none} when no
   *     tokens were consumed or the used model is unknown (on a miss)
   */
  public GreenMetrics account(ModelSite used,
                              ModelSite baseline,
                              TokenUsage usage,
                              boolean cacheHit) {
    if (usage == null || usage.totalTokens() <= 0) {
      return GreenMetrics.none(currency);
    }
    ModelDefinition baselineModel = baseline == null ? null : baseline.model();

    if (cacheHit) {
      double avoidedCo2 = carbonCalculator.estimate(baseline, usage).gramsCo2();
      if (baselineModel == null) {
        return new GreenMetrics(0.0, 0.0, 0.0, 0.0, avoidedCo2, PricingBasis.NONE, currency);
      }
      ModelPricing price = baselineModel.pricing();
      return new GreenMetrics(0.0, 0.0, 0.0, price.cost(usage), avoidedCo2, price.basis(),
          currency);
    }

    if (used == null || used.model() == null) {
      return GreenMetrics.none(currency);
    }

    ModelPricing price = used.model().pricing();
    double cost = price.cost(usage);
    CarbonFootprint actual = carbonCalculator.estimate(used, usage);

    double gramsCo2Avoided = 0.0;
    double costAvoided = 0.0;
    if (baselineModel != null) {
      CarbonFootprint baselineFootprint = carbonCalculator.estimate(baseline, usage);
      gramsCo2Avoided =
          Math.max(0.0, baselineFootprint.gramsCo2() - actual.gramsCo2());
      costAvoided = Math.max(0.0, baselineModel.pricing().cost(usage) - cost);
    }

    return new GreenMetrics(cost, actual.energyKwh(), actual.gramsCo2(),
        costAvoided, gramsCo2Avoided, price.basis(), currency);
  }

  private static String isoCurrency(String code) {
    String trimmed = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    try {
      return Currency.getInstance(trimmed).getCurrencyCode();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("gatewai.billing.currency='" + code
          + "' is not an ISO 4217 currency code (EUR, USD, GBP…).", e);
    }
  }
}
