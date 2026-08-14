package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;

/**
 * One request's decisions, exactly as persisted (v2 batch 9).
 *
 * <p>Nothing here is recomputed. `GET /v1/admin/decisions/{correlationId}` is
 * the "what happened" endpoint, and the whole point of having stored the rows is
 * that they still describe the rules of that moment rather than today's.
 *
 * <p>Either section may be null, and that is information: no {@code routing}
 * means the cache answered the request and the router never ran.
 *
 * @param correlationId the id joining both rows and the carbon record
 * @param at            when the request was decided
 * @param cache         the cache decision, null when none was recorded
 * @param routing       the routing decision, null on a cache hit
 */
record DecisionView(String correlationId, Instant at, Cache cache,
                    Routing routing) {

  static DecisionView of(TracedDecision decision) {
    return new DecisionView(decision.correlationId(), decision.at(),
        Cache.of(decision.cache()), Routing.of(decision.routing()));
  }

  /**
   * The cache decision as taken.
   *
   * @param outcome                HIT, MISS or BYPASS
   * @param similarityScore        best candidate's similarity
   * @param runnerUpScore          second best, null when there was no second
   *                               candidate — the implicit margin
   * @param threshold              the acceptance threshold in force then
   * @param conformalStatus        what the calibrated set looked like: an
   *                               AMBIGUOUS miss is a deliberate refusal and
   *                               reads nothing like an empty one
   * @param matchedEntryId         the served entry, null unless HIT
   * @param matchedEntryAgeSeconds how old it was when served
   * @param originCorrelationId    the request that wrote the served entry —
   *                               follow it to see how that answer was routed
   * @param embeddingModel         provenance of the vectors
   */
  record Cache(String outcome, double similarityScore, Double runnerUpScore,
               double threshold, String conformalStatus, String matchedEntryId,
               Long matchedEntryAgeSeconds, String originCorrelationId,
               String embeddingModel) {

    static Cache of(CacheDecision decision) {
      if (decision == null) {
        return null;
      }
      return new Cache(name(decision.outcome()), decision.similarityScore(),
          decision.runnerUpScore(), decision.threshold(),
          name(decision.conformalStatus()), decision.matchedEntryId(),
          decision.matchedEntryAgeSeconds(), decision.originCorrelationId(),
          decision.embeddingModel());
    }
  }

  /**
   * The routing decision as taken.
   *
   * @param chosenTier          where it went
   * @param chosenModelId       what it was rewritten to, null on a pass-through
   * @param decisionReason      one-word summary of the justification
   * @param strategy            the configured strategy
   * @param effectiveStrategy   the one that actually decided — different on a
   *                            hand-over, which is what distinguishes a
   *                            degraded decision from a nominal one
   * @param escalatedTo         deepest cascade level reached, null outside the
   *                            cascade
   * @param routingLatencyMs    time spent deciding, excluding the LLM call
   * @param justification       the full reason, serialized as it was stored
   * @param confidence          how close the decision was
   * @param promptHash          SHA-256 of the classified text — the only trace
   *                            of the prompt that exists
   * @param promptLength        its length in characters
   * @param embeddingModel      provenance of the vectors
   * @param routingConfigVersion the rules in force then
   */
  record Routing(String chosenTier, String chosenModelId, String decisionReason,
                 String strategy, String effectiveStrategy, String escalatedTo,
                 long routingLatencyMs,
                 ClassificationJustification justification,
                 Confidence confidence, String promptHash, int promptLength,
                 String embeddingModel, String routingConfigVersion) {

    static Routing of(RoutingDecision decision) {
      if (decision == null) {
        return null;
      }
      return new Routing(name(decision.chosenTier()), decision.chosenModelId(),
          name(decision.decisionReason()), name(decision.strategy()),
          name(decision.effectiveStrategy()), name(decision.escalatedTo()),
          decision.routingLatencyMs(), decision.justification(),
          Confidence.of(decision), decision.promptHash(),
          decision.promptLength(), decision.embeddingModel(),
          decision.routingConfigVersion());
    }
  }

  /**
   * How close the decision was, lifted out of the justification because it is
   * the part an operator reads first.
   *
   * @param topScore     the winning similarity, null when nothing was embedded
   * @param margin       {@code top1 − top2}: the number the cascade escalates on
   * @param threshold    the route-similarity threshold in force
   * @param conformalSet the tiers that cleared the calibrated threshold. Null
   *                     means no calibration was in force, which is not the same
   *                     as an empty set — there, one applied and nothing
   *                     qualified
   * @param alpha        the risk level that set was built at
   */
  record Confidence(Double topScore, Double margin, Double threshold,
                    List<String> conformalSet, Double alpha) {

    static Confidence of(RoutingDecision decision) {
      var scores = ClassificationJustification.routeScores(decision.justification());
      return new Confidence(
          scores.map(ClassificationJustification.Embedding::topScore).orElse(null),
          scores.map(ClassificationJustification.Embedding::margin).orElse(null),
          scores.map(ClassificationJustification.Embedding::threshold).orElse(null),
          tiers(decision.conformalSet()), decision.conformalAlpha());
    }

    private static List<String> tiers(List<ModelTier> set) {
      return set == null ? null : set.stream().map(Enum::name).toList();
    }
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
