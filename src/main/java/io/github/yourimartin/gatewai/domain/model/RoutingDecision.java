package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One routing decision, as taken (v2 batch 2).
 *
 * <p>Recorded so a decision can be explained after the fact rather than
 * reconstructed — the routing configuration is editable in production, so a
 * decision rebuilt later would be explaining today's rules, not the ones that
 * actually applied. {@code routingConfigVersion} is what makes that detectable.
 *
 * @param id                    surrogate id
 * @param correlationId         the ingress-assigned id shared with the
 *                              {@link RequestLog} of the same request
 * @param createdAt             when the decision was taken
 * @param promptHash            SHA-256 of the <b>classified user text</b>. Not
 *                              the same input as {@code RequestLog.promptHash},
 *                              which covers every message: join on the
 *                              correlation id, never on the hash
 * @param promptLength          length of that text, in characters
 * @param embeddingModel        which model produced the vectors, when one did
 * @param routingConfigVersion  hash of the routing rules in force
 * @param strategy              the configured strategy
 * @param effectiveStrategy     the strategy that actually decided — differs
 *                              from {@code strategy} on a hand-over
 * @param justification         the full reason, serialized as JSON
 * @param decisionReason        one-word summary of the justification
 * @param chosenTier            the tier the request was classified into
 * @param chosenModelId         the model it was rewritten to, null when the
 *                              router passed the request through
 * @param routingLatencyMs      time spent deciding, excluding the LLM call
 * @param conformalSet          the tiers whose route cleared the calibrated
 *                              threshold (v2 batch 3), best first.
 *                              <b>Null</b> when no calibration was in force —
 *                              which is not the same as an empty set, where a
 *                              calibration applied and nothing qualified
 * @param conformalAlpha        the risk level that set was built at, null with
 *                              {@code conformalSet}
 */
public record RoutingDecision(
    UUID id,
    String correlationId,
    Instant createdAt,
    String promptHash,
    int promptLength,
    String embeddingModel,
    String routingConfigVersion,
    ClassificationStrategy strategy,
    ClassificationStrategy effectiveStrategy,
    ClassificationJustification justification,
    DecisionReason decisionReason,
    ModelTier chosenTier,
    String chosenModelId,
    long routingLatencyMs,
    List<ModelTier> conformalSet,
    Double conformalAlpha
) {

  public RoutingDecision {
    conformalSet = conformalSet == null ? null : List.copyOf(conformalSet);
  }
}
