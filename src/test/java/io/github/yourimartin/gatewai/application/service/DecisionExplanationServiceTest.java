package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.model.AttributionStatus;
import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.Counterfactual;
import io.github.yourimartin.gatewai.domain.model.CounterfactualReport;
import io.github.yourimartin.gatewai.domain.model.CounterfactualStatus;
import io.github.yourimartin.gatewai.domain.model.DecisionExplanation;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.SegmentAttribution;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;
import io.github.yourimartin.gatewai.domain.port.in.PromptAttributionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.RouteCounterfactualUseCase;
import io.github.yourimartin.gatewai.domain.port.out.DecisionHistory;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What this service owns is which questions are answerable about which request,
 * so that is what the tests are about: a stored decision has a trace and no
 * re-embeddable prompt, a prompt has the analysis and no decision.
 */
class DecisionExplanationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");
  private static final Instant CALIBRATED_AT =
      Instant.parse("2026-08-01T09:00:00Z");

  private StubHistory history;
  private StubCalibrations calibrations;
  private DecisionExplanationService service;

  @BeforeEach
  void setUp() {
    history = new StubHistory();
    calibrations = new StubCalibrations(CalibrationStatus.VALID);
    service = new DecisionExplanationService(history, new StubAttribution(),
        new StubCounterfactuals(), calibrations, new StubRoutingConfig(),
        new StubEmbedder());
  }

  @Test
  @DisplayName("a past decision comes back whole, and is not recomputed")
  void explainsAStoredDecisionFromTheRow() {
    history.put(traced("req-1", routing("cfg-old")));

    DecisionExplanation explanation = service.explain("req-1").orElseThrow();

    assertEquals(ModelTier.CLOUD_PREMIUM,
        explanation.decision().routing().chosenTier());
    assertEquals("cfg-old", explanation.provenance().routingConfigVersion(),
        "a decision keeps the rules it was taken under, not today's");
    assertEquals("nomic-embed-text", explanation.provenance().embeddingModel());
    assertEquals(CALIBRATED_AT, explanation.provenance().calibrationDate());
    assertEquals(CalibrationStatus.VALID,
        explanation.provenance().calibrationStatus());
  }

  @Test
  @DisplayName("the prompt is gone, so the analysis says so rather than nothing")
  void storedDecisionsCannotBeReEmbedded() {
    history.put(traced("req-1", routing("cfg-old")));

    DecisionExplanation explanation = service.explain("req-1").orElseThrow();

    assertEquals(AttributionStatus.PROMPT_UNAVAILABLE,
        explanation.attribution().status());
    assertEquals(CounterfactualStatus.PROMPT_UNAVAILABLE,
        explanation.counterfactuals().status());
    assertTrue(explanation.attribution().segments().isEmpty());
  }

  @Test
  @DisplayName("a cache hit has no routing row, and no routing rules to report")
  void cacheHitProvenanceFallsBackToTheCacheRow() {
    history.put(new TracedDecision("req-2", NOW, cacheHit(), null));

    DecisionExplanation explanation = service.explain("req-2").orElseThrow();

    assertNull(explanation.decision().routing());
    assertNull(explanation.provenance().routingConfigVersion(),
        "the router never ran, so no version governed this request");
    assertEquals("nomic-embed-text", explanation.provenance().embeddingModel());
    assertEquals(CalibrationTarget.CACHE, calibrations.lastAsked,
        "a cache hit was governed by the cache calibration, not the router's");
  }

  @Test
  @DisplayName("an unknown correlation id is empty, in both read paths")
  void unknownCorrelationId() {
    assertTrue(service.explain("nope").isEmpty());
    assertTrue(service.find("nope").isEmpty());
    assertTrue(service.find(null).isEmpty());
  }

  @Test
  @DisplayName("explaining a prompt analyses it, and attaches no decision")
  void explainsAPromptAgainstCurrentRules() {
    DecisionExplanation explanation =
        service.explainPrompt("Refactor the architecture.");

    assertNull(explanation.decision(),
        "no request was made, so claiming a decision would be a fiction");
    assertEquals(AttributionStatus.COMPUTED, explanation.attribution().status());
    assertEquals(CounterfactualStatus.COMPUTED,
        explanation.counterfactuals().status());
    assertEquals(1, explanation.counterfactuals().alternatives().size());
    assertNotNull(explanation.provenance().routingConfigVersion());
    assertEquals("nomic-embed-text", explanation.provenance().embeddingModel());
  }

  @Test
  @DisplayName("a stale calibration is reported as stale, not hidden")
  void staleCalibrationSurfacesInProvenance() {
    calibrations = new StubCalibrations(CalibrationStatus.STALE);
    service = new DecisionExplanationService(history, new StubAttribution(),
        new StubCounterfactuals(), calibrations, new StubRoutingConfig(),
        new StubEmbedder());

    assertEquals(CalibrationStatus.STALE,
        service.explainPrompt("Refactor.").provenance().calibrationStatus());
  }

  @Test
  @DisplayName("the history is passed through, bounded")
  void recentDelegates() {
    history.put(traced("req-1", routing("cfg-1")));

    assertEquals(1, service.recent(10).size());
    assertTrue(service.recent(0).isEmpty());
  }

  private static TracedDecision traced(String correlationId,
                                       RoutingDecision routing) {
    return new TracedDecision(correlationId, NOW, null, routing);
  }

  private static RoutingDecision routing(String configVersion) {
    return new RoutingDecision(UUID.randomUUID(), "req-1", NOW,
        "a".repeat(64), 42, "nomic-embed-text", configVersion,
        ClassificationStrategy.EMBEDDING, ClassificationStrategy.EMBEDDING,
        new ClassificationJustification.Embedding(List.of(), 0.81, 0.12, 0.60),
        DecisionReason.MATCH, ModelTier.CLOUD_PREMIUM, "qwen3:14b", 12L,
        List.of(ModelTier.CLOUD_PREMIUM), 0.05, null);
  }

  private static CacheDecision cacheHit() {
    return new CacheDecision(UUID.randomUUID(), "req-2", NOW, "b".repeat(64),
        CacheOutcome.HIT, 0.97, 0.42, 0.92, "entry-1", 30L, "origin-1",
        "nomic-embed-text", ConformalStatus.SINGLETON);
  }

  private static final class StubHistory implements DecisionHistory {

    private final java.util.Map<String, TracedDecision> decisions =
        new java.util.LinkedHashMap<>();

    void put(TracedDecision decision) {
      decisions.put(decision.correlationId(), decision);
    }

    @Override
    public Optional<TracedDecision> byCorrelationId(String correlationId) {
      return Optional.ofNullable(decisions.get(correlationId));
    }

    @Override
    public List<TracedDecision> recent(int limit) {
      return decisions.values().stream().limit(limit).toList();
    }
  }

  private static final class StubAttribution implements PromptAttributionUseCase {

    @Override
    public AttributionReport attribute(String prompt) {
      return new AttributionReport(AttributionStatus.COMPUTED, "code",
          ModelTier.CLOUD_PREMIUM, "Refactor this service", 0.81,
          List.of(new SegmentAttribution("Refactor the architecture.", 0.4,
              1.0, 1)),
          "nomic-embed-text", "cfg-now");
    }
  }

  private static final class StubCounterfactuals
      implements RouteCounterfactualUseCase {

    @Override
    public CounterfactualReport explore(String prompt) {
      return new CounterfactualReport(CounterfactualStatus.COMPUTED, "code",
          ModelTier.CLOUD_PREMIUM, "Refactor this service", 0.81,
          List.of(new Counterfactual("chat", ModelTier.LOCAL, "Hello there",
              0.41, 0.40, 1)),
          "nomic-embed-text", "cfg-now");
    }
  }

  private static final class StubCalibrations
      implements io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase {

    private final CalibrationStatus status;
    private CalibrationTarget lastAsked;

    StubCalibrations(CalibrationStatus status) {
      this.status = status;
    }

    @Override
    public CalibrationState state(CalibrationTarget target) {
      lastAsked = target;
      return new CalibrationState(target, status,
          new ConformalCalibration(target,
              ConformalGuarantee.CORRECT_TARGET_COVERAGE, 0.05, 0.17, 120,
              "nomic-embed-text", "cfg-old", CALIBRATED_AT),
          0.60);
    }

    @Override
    public List<CalibrationState> states() {
      return List.of(state(CalibrationTarget.ROUTING),
          state(CalibrationTarget.CACHE));
    }

    @Override
    public List<CalibrationState> recalibrate(Double routingAlpha,
                                              Double cacheAlpha) {
      throw new UnsupportedOperationException("not part of an explanation");
    }
  }

  private static final class StubRoutingConfig implements RoutingConfigPort {

    @Override
    public RoutingConfig get() {
      return new RoutingConfig("embedding", 100, 500, List.of(), 0.6,
          List.of(new SemanticRoute("code", ModelTier.CLOUD_PREMIUM,
              List.of("Refactor this service"))));
    }

    @Override
    public void update(RoutingConfig config) {
      throw new UnsupportedOperationException("read-only in this test");
    }

    @Override
    public double cascadeMarginBand() {
      return 0.02;
    }

    @Override
    public void updateCascadeMarginBand(double band) {
      throw new UnsupportedOperationException("not part of this test");
    }
  }

  private static final class StubEmbedder implements TextEmbedder {

    @Override
    public float[] embed(String text) {
      throw new UnsupportedOperationException(
          "an explanation must not embed anything itself");
    }

    @Override
    public String modelId() {
      return "nomic-embed-text";
    }
  }
}
