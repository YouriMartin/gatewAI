package io.github.yourimartin.gatewai.domain.model.report;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;

/**
 * The money side of a {@link GreenReport} (v4 B.6): what was spent and avoided,
 * in which currency, and on which pricing basis.
 *
 * <p>Costs are summed per currency and never across currencies: the gateway does
 * no conversion, so a period during which {@code gatewai.billing.currency} changed
 * has one total per currency and no grand total. That is the only case where
 * {@link #currency()} is {@code null}.
 *
 * @param currency              the period's single currency; the configured one
 *                              when the period is empty; {@code null} when rows
 *                              in several currencies fall in it
 * @param costByCurrency        cost incurred, per ISO 4217 code
 * @param costAvoidedByCurrency cost avoided vs the premium baseline, per code
 * @param requestsByBasis       requests per pricing basis
 * @param cachedTokensUnreported split-priced inferences whose provider did not
 *                              report cached prompt tokens, so every prompt token
 *                              was priced at the input rate
 */
public record CostSummary(
    String currency,
    Map<String, Double> costByCurrency,
    Map<String, Double> costAvoidedByCurrency,
    Map<PricingBasis, Long> requestsByBasis,
    long cachedTokensUnreported
) {

  public CostSummary {
    costByCurrency = costByCurrency == null ? Map.of() : Map.copyOf(costByCurrency);
    costAvoidedByCurrency =
        costAvoidedByCurrency == null ? Map.of() : Map.copyOf(costAvoidedByCurrency);
    requestsByBasis = requestsByBasis == null || requestsByBasis.isEmpty()
        ? Map.of() : Map.copyOf(new EnumMap<>(requestsByBasis));
  }

  /** One currency, no basis mix: what a test or an empty period needs. */
  public static CostSummary of(String currency, double cost, double costAvoided) {
    Map<String, Double> costs = new LinkedHashMap<>();
    costs.put(currency, cost);
    Map<String, Double> avoided = new LinkedHashMap<>();
    avoided.put(currency, costAvoided);
    return new CostSummary(currency, costs, avoided, Map.of(), 0);
  }

  /** Whether rows in more than one currency fall in the period. */
  public boolean mixedCurrencies() {
    return currency == null;
  }

  /** Cost incurred in {@link #currency()}; zero when the currencies are mixed. */
  public double totalCost() {
    return currency == null ? 0.0 : costByCurrency.getOrDefault(currency, 0.0);
  }

  /** Cost avoided in {@link #currency()}; zero when the currencies are mixed. */
  public double totalCostAvoided() {
    return currency == null ? 0.0 : costAvoidedByCurrency.getOrDefault(currency, 0.0);
  }

  /**
   * The period's pricing basis: the one every request was priced on,
   * {@code MIXED} when there are several, {@code NONE} when there was nothing.
   */
  public String basis() {
    List<PricingBasis> used = requestsByBasis.entrySet().stream()
        .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).sorted().toList();
    if (used.isEmpty()) {
      return PricingBasis.NONE.name();
    }
    return used.size() == 1 ? used.getFirst().name() : "MIXED";
  }

  /**
   * One sentence saying what the cost figures are priced on, rendered verbatim by
   * every view so they cannot disagree.
   */
  public String pricingNote() {
    StringBuilder note = new StringBuilder();
    long blended = requestsByBasis.getOrDefault(PricingBasis.BLENDED, 0L);
    long none = requestsByBasis.getOrDefault(PricingBasis.NONE, 0L);
    long split = requestsByBasis.getOrDefault(PricingBasis.SPLIT, 0L);
    if (split + blended + none == 0) {
      return "No requests in this period.";
    }
    note.append(switch (basis()) {
      case "SPLIT" -> "Every request is priced at split input/output/cached-input rates.";
      case "BLENDED" -> "Every request is priced at a blended rate per 1000 tokens, which"
          + " overstates input-heavy traffic.";
      case "NONE" -> "No request was priced: the cost is zero because no price is declared,"
          + " not because the requests were free.";
      default -> "Pricing basis is mixed: " + split + " split, " + blended + " blended, "
          + none + " not priced (cost zero because no price is declared).";
    });
    if (cachedTokensUnreported > 0) {
      note.append(' ').append(cachedTokensUnreported).append(" split-priced request(s) did not"
          + " report cached prompt tokens; their whole prompt is priced at the input rate.");
    }
    if (mixedCurrencies()) {
      note.append(" Costs are in several currencies (").append(String.join(", ",
          costByCurrency.keySet().stream().sorted().toList())).append(") and are not added up:"
          + " no conversion is done.");
    }
    return note.toString();
  }
}
