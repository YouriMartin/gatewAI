package io.github.yourimartin.gatewai.domain.model.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.carbon.EnergySource;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;

import org.junit.jupiter.api.Test;

/** The money side of a report (v4 B.6): currencies are never added together. */
class CostSummaryTest {

  private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-10-31T00:00:00Z");
  private final ReportAggregator aggregator =
      new ReportAggregator(model -> EnergySource.MODELLED, "EUR");

  @Test
  void oneCurrencyAndOneBasisGiveATotalAndASingleLabel() {
    CostSummary costs = aggregate(
        row(0.02, 0.10, PricingBasis.SPLIT, "USD", 0),
        row(0.03, 0.20, PricingBasis.SPLIT, "USD", 0));

    assertThat(costs.currency()).isEqualTo("USD");
    assertThat(costs.totalCost()).isCloseTo(0.05, within(1e-12));
    assertThat(costs.totalCostAvoided()).isCloseTo(0.30, within(1e-12));
    assertThat(costs.basis()).isEqualTo("SPLIT");
    assertThat(costs.pricingNote()).startsWith("Every request is priced at split");
  }

  @Test
  void severalCurrenciesAreKeptApartWithNoGrandTotal() {
    CostSummary costs = aggregate(
        row(1.0, 0.0, PricingBasis.BLENDED, "EUR", 0),
        row(2.0, 0.0, PricingBasis.SPLIT, "USD", 0));

    assertThat(costs.mixedCurrencies()).isTrue();
    assertThat(costs.currency()).isNull();
    assertThat(costs.costByCurrency()).containsEntry("EUR", 1.0).containsEntry("USD", 2.0);
    assertThat(costs.totalCost()).isZero();
    assertThat(costs.pricingNote()).contains("several currencies (EUR, USD)")
        .contains("not added up");
  }

  @Test
  void aBasisMixIsCountedAndSaysWhatAZeroMeans() {
    CostSummary costs = aggregate(
        row(0.02, 0.0, PricingBasis.SPLIT, "EUR", 0),
        row(0.0, 0.05, PricingBasis.NONE, "EUR", 0),
        row(0.0, 0.05, PricingBasis.NONE, "EUR", 0));

    assertThat(costs.basis()).isEqualTo("MIXED");
    assertThat(costs.requestsByBasis())
        .containsEntry(PricingBasis.SPLIT, 1L).containsEntry(PricingBasis.NONE, 2L);
    assertThat(costs.pricingNote()).contains("1 split, 0 blended, 2 not priced")
        .contains("no price is declared");
  }

  @Test
  void splitRowsWithoutACachedCountAreCalledOut() {
    RequestLog unreported = row(0.02, 0.0, PricingBasis.SPLIT, "EUR", null);

    CostSummary costs = aggregate(unreported, row(0.02, 0.0, PricingBasis.SPLIT, "EUR", 0));

    assertThat(costs.cachedTokensUnreported()).isEqualTo(1);
    assertThat(costs.pricingNote())
        .contains("1 split-priced request(s) did not report cached prompt tokens");
  }

  @Test
  void anEmptyPeriodReportsInTheConfiguredCurrency() {
    CostSummary costs = aggregate();

    assertThat(costs.currency()).isEqualTo("EUR");
    assertThat(costs.basis()).isEqualTo("NONE");
    assertThat(costs.pricingNote()).isEqualTo("No requests in this period.");
  }

  private CostSummary aggregate(RequestLog... rows) {
    return aggregator.aggregate(List.of(rows), FROM, TO).costs();
  }

  private static RequestLog row(double cost, double avoided, PricingBasis basis,
                                String currency, Integer cachedPromptTokens) {
    return new RequestLog(UUID.randomUUID(), "corr", FROM, "model", "hash", 1000, 50, 1050,
        10L, "client", new GreenMetrics(cost, 0.001, 0.1, avoided, 0.0, basis, currency),
        GreenProvenance.UNKNOWN, false, cachedPromptTokens);
  }
}
