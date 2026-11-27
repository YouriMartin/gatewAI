package io.github.yourimartin.gatewai.domain.model.shadow;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;

/**
 * What one shadow request would have cost had gatewAI enforced its decision (v4 D.4,
 * ADR 0020), priced by the replay when it decided and stored self-describing, so a
 * report re-derives it from this row and never from today's configuration.
 *
 * @param id            surrogate id
 * @param correlationId the shadow request's, joining its {@code request_log} row
 *                      (what was paid) and its {@code SHADOW} decisions
 * @param clientId      the shadow client
 * @param createdAt     when it was priced
 * @param outcome       what the chain would have done
 * @param model         the model that would have answered; null exactly on a
 *                      {@link CounterfactualOutcome#WOULD_HIT}
 * @param usage         the usage it was priced with: the answer received's, since
 *                      the routed model never ran
 * @param metrics       cost, energy and CO2 it would have cost. A counterfactual avoids
 *                      nothing, so the avoided figures are always zero.
 * @param provenance    where {@code model} would have run, as it was then
 */
public record ShadowCounterfactual(
    UUID id,
    String correlationId,
    String clientId,
    Instant createdAt,
    CounterfactualOutcome outcome,
    String model,
    TokenUsage usage,
    GreenMetrics metrics,
    GreenProvenance provenance
) {

  public ShadowCounterfactual {
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(usage, "usage");
    Objects.requireNonNull(metrics, "metrics");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(metrics.currency(), "a priced counterfactual states its currency");
    boolean wouldHit = outcome == CounterfactualOutcome.WOULD_HIT;
    if (wouldHit != (model == null)) {
      throw new IllegalArgumentException(
          "a would-hit names no model; a routed or passed-through request names one");
    }
    if (wouldHit && (metrics.cost() != 0 || metrics.energyKwh() != 0
        || metrics.gramsCo2() != 0)) {
      throw new IllegalArgumentException("a would-hit runs no inference and costs nothing");
    }
    metrics = new GreenMetrics(metrics.cost(), metrics.energyKwh(), metrics.gramsCo2(),
        0.0, 0.0, metrics.pricingBasis(), metrics.currency());
  }
}
