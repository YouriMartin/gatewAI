package io.github.yourimartin.gatewai.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerDecisionMetricsRecorderTest {

  private MeterRegistry registry;
  private MicrometerDecisionMetricsRecorder recorder;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    recorder = new MicrometerDecisionMetricsRecorder(registry);
  }

  @Test
  @DisplayName("a routing decision is counted by tier, reason and strategy")
  void routingDecisionsAreCountedByTheirShape() {
    recorder.record(routing(ModelTier.LOCAL, DecisionReason.MATCH,
        ClassificationStrategy.EMBEDDING, null, null, embedding(0.42)));

    assertEquals(1.0, registry.get("gatewai.routing.decisions")
        .tag("tier", "local")
        .tag("reason", "match")
        .tag("strategy", "embedding")
        .counter().count());
  }

  @Test
  @DisplayName("the margin rides on the decision, whichever variant carries it")
  void marginIsRecordedPerTier() {
    // A below-threshold hand-over: the heuristic decided, the scores are
    // evidence — and that is exactly the case worth watching for drift.
    ClassificationJustification handedOver =
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING,
            ClassificationJustification.FallbackCause.BELOW_THRESHOLD,
            ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT),
            embedding(0.11));

    recorder.record(routing(ModelTier.CLOUD_ENTRY,
        DecisionReason.BELOW_THRESHOLD_FALLBACK,
        ClassificationStrategy.EMBEDDING, null, null, handedOver));

    var summary = registry.get("gatewai.routing.margin")
        .tag("tier", "cloud_entry").summary();
    assertEquals(1, summary.count());
    assertEquals(0.11, summary.totalAmount(), 1e-9);
  }

  @Test
  @DisplayName("a decision with no route scores records no margin")
  void heuristicDecisionsHaveNoMargin() {
    recorder.record(routing(ModelTier.LOCAL, DecisionReason.MATCH,
        ClassificationStrategy.HEURISTIC, null, null,
        ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT)));

    assertTrue(registry.find("gatewai.routing.margin").summaries().isEmpty(),
        "an unmeasured margin must be absent, not zero");
  }

  @Test
  @DisplayName("every cascade level is counted, so the escalation rate is a ratio")
  void cascadeLevelsAreCountedIncludingTheCheapOnes() {
    recorder.record(routing(ModelTier.CLOUD_PREMIUM, DecisionReason.MATCH,
        ClassificationStrategy.CASCADE, CascadeLevel.EMBEDDING, null,
        embedding(0.30)));
    recorder.record(routing(ModelTier.CLOUD_PREMIUM,
        DecisionReason.AMBIGUOUS_ESCALATED, ClassificationStrategy.CASCADE,
        CascadeLevel.LLM, null, embedding(0.01)));

    assertEquals(1.0, escalations("embedding"));
    assertEquals(1.0, escalations("llm"));
  }

  @Test
  @DisplayName("a non-cascade decision contributes nothing to the escalation rate")
  void noLevelMeansNoSeries() {
    recorder.record(routing(ModelTier.LOCAL, DecisionReason.MATCH,
        ClassificationStrategy.EMBEDDING, null, null, embedding(0.30)));

    assertTrue(registry.find("gatewai.cascade.escalations").counters().isEmpty(),
        "counting the whole traffic here would make the ratio meaningless");
  }

  @Test
  @DisplayName("the prediction set's size is recorded only when one existed")
  void predictionSetSizeIsRecordedOnlyUnderACalibration() {
    recorder.record(routing(ModelTier.LOCAL, DecisionReason.MATCH,
        ClassificationStrategy.EMBEDDING, null,
        List.of(ModelTier.LOCAL, ModelTier.CLOUD_ENTRY), embedding(0.05)));
    recorder.record(routing(ModelTier.LOCAL, DecisionReason.MATCH,
        ClassificationStrategy.EMBEDDING, null, null, embedding(0.30)));

    var summary = registry.get("gatewai.conformal.set.size")
        .tag("target", "routing").summary();
    assertEquals(1, summary.count(),
        "no calibration is not a set of size zero");
    assertEquals(2.0, summary.totalAmount(), 1e-9);
  }

  @Test
  @DisplayName("a pinned decision is counted, with 'none' where nothing decided")
  void pinnedDecisionsAreCountedToo() {
    recorder.record(new RoutingDecision(UUID.randomUUID(), "corr", Instant.now(),
        "hash", 10, null, "cfg", ClassificationStrategy.EMBEDDING, null, null,
        DecisionReason.CLIENT_PINNED, ModelTier.CLOUD_PREMIUM, "qwen2.5:3b",
        1L, null, null, null));

    assertEquals(1.0, registry.get("gatewai.routing.decisions")
        .tag("reason", "client_pinned")
        .tag("tier", "cloud_premium")
        .counter().count());
  }

  @Test
  @DisplayName("cache decisions carry both the outcome and the set's shape")
  void cacheDecisionsAreCountedByOutcomeAndStatus() {
    recorder.record(cache(CacheOutcome.MISS, ConformalStatus.AMBIGUOUS, 0.93));

    assertEquals(1.0, registry.get("gatewai.cache.decisions")
        .tag("outcome", "miss")
        .tag("conformal_status", "ambiguous")
        .counter().count(),
        "a refusal on ambiguity must not read as a plain miss");
    assertEquals(0.93, registry.get("gatewai.cache.similarity")
        .summary().totalAmount(), 1e-9);
  }

  @Test
  @DisplayName("a bypass scores nothing, so it is not folded into the similarities")
  void bypassDoesNotPolluteTheSimilarityDistribution() {
    recorder.record(cache(CacheOutcome.BYPASS, null, 0));

    assertEquals(1.0, registry.get("gatewai.cache.decisions")
        .tag("outcome", "bypass")
        .tag("conformal_status", "none")
        .counter().count());
    assertTrue(registry.find("gatewai.cache.similarity").summaries().isEmpty());
  }

  @Test
  @DisplayName("a registry that throws costs the metric, never the request")
  void metricFailuresAreCountedRatherThanThrown() {
    MeterRegistry broken = mock(MeterRegistry.class);
    when(broken.counter(anyString(), any(String[].class)))
        .thenThrow(new IllegalStateException("registry unavailable"));
    var fragile = new MicrometerDecisionMetricsRecorder(broken);

    assertDoesNotThrow(() -> fragile.record(routing(ModelTier.LOCAL,
        DecisionReason.MATCH, ClassificationStrategy.EMBEDDING, null, null,
        embedding(0.30))));
    assertDoesNotThrow(() ->
        fragile.record(cache(CacheOutcome.HIT, ConformalStatus.SINGLETON, 0.95)));
  }

  @Test
  @DisplayName("names are registered dotted, as the rest of the stack does (D8)")
  void meterNamesAreDotted() {
    recorder.record(routing(ModelTier.LOCAL, DecisionReason.MATCH,
        ClassificationStrategy.EMBEDDING, null, null, embedding(0.30)));

    assertNull(registry.find("gatewai_routing_decisions_total").meter(),
        "the underscored form is Prometheus' rendering, not the registered name");
    assertEquals(1.0,
        registry.get("gatewai.routing.decisions").counter().count());
  }

  private double escalations(String level) {
    return registry.get("gatewai.cascade.escalations")
        .tag("to_level", level).counter().count();
  }

  private static ClassificationJustification.Embedding embedding(double margin) {
    return new ClassificationJustification.Embedding(List.of(
        new ClassificationJustification.RouteCandidate(
            "casual-chat", ModelTier.LOCAL, "hello", 0.71, 1)),
        0.71, margin, 0.60);
  }

  private static RoutingDecision routing(
      ModelTier tier, DecisionReason reason, ClassificationStrategy strategy,
      CascadeLevel escalatedTo, List<ModelTier> conformalSet,
      ClassificationJustification justification) {

    return new RoutingDecision(UUID.randomUUID(), "corr", Instant.now(),
        "hash", 42, "nomic-embed-text", "cfg", strategy,
        justification.strategy(), justification, reason, tier, "model-x", 3L,
        conformalSet, conformalSet == null ? null : 0.10, escalatedTo);
  }

  private static CacheDecision cache(CacheOutcome outcome,
                                     ConformalStatus status, double score) {
    return new CacheDecision(UUID.randomUUID(), "corr", Instant.now(), "hash",
        outcome, score, null, 0.94, null, null, null, "nomic-embed-text",
        status);
  }
}
