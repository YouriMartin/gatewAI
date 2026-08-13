package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.RouteCandidate;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cascade's gates (v2 batch 4). What matters here is not the tiers — the
 * three classifiers are tested on their own — but <b>when a level is paid for</b>:
 * every escalation costs a model call, and every non-escalation is a decision
 * taken on cheaper evidence.
 */
class CascadeComplexityClassifierTest {

  private static final double THRESHOLD = 0.60;

  private ClassifierProperties properties;
  private EmbeddingComplexityClassifier embedding;
  private LlmComplexityClassifier llm;

  @BeforeEach
  void setUp() {
    properties = new ClassifierProperties();
    embedding = mock(EmbeddingComplexityClassifier.class);
    llm = mock(LlmComplexityClassifier.class);
  }

  @Test
  @DisplayName("a code fence is decided for free, before anything is embedded")
  void deterministicSignalShortCircuitsTheWholeCascade() {
    ClassificationOutcome outcome = cascade(calibrated())
        .classify("```java\nvar x = 1;\n```");

    ClassificationJustification.Cascade justification = cascadeOf(outcome);
    assertEquals(CascadeLevel.DETERMINISTIC, justification.level());
    assertEquals(ModelTier.CLOUD_PREMIUM, outcome.tier());
    assertEquals(HeuristicRule.CODE_FENCE,
        ((ClassificationJustification.Heuristic) justification.decided()).rule());
    verify(embedding, never()).classify(anyString());
    verify(llm, never()).classify(anyString());
  }

  @Test
  @DisplayName("keywords are not a deterministic signal: they go through the routes")
  void premiumKeywordsDoNotShortCircuit() {
    when(embedding.classify("refactor this"))
        .thenReturn(routed(ModelTier.CLOUD_PREMIUM, 0.80, 0.30, 0.20));

    ClassificationOutcome outcome = cascade(calibrated()).classify("refactor this");

    assertEquals(CascadeLevel.EMBEDDING, cascadeOf(outcome).level());
    verify(embedding).classify("refactor this");
  }

  @Test
  @DisplayName("one tier in the prediction set decides: no model call")
  void singletonPredictionSetStopsAtTheRoutes() {
    when(embedding.classify("hello"))
        .thenReturn(routed(ModelTier.LOCAL, 0.90, 0.30, 0.20));

    ClassificationOutcome outcome = cascade(calibrated()).classify("hello");

    assertEquals(CascadeLevel.EMBEDDING, cascadeOf(outcome).level());
    assertEquals(ModelTier.LOCAL, outcome.tier());
    assertNull(cascadeOf(outcome).escalatedOn(),
        "nothing was escalated on, so there is nothing to explain");
    verify(llm, never()).classify(anyString());
  }

  @Test
  @DisplayName("several tiers within the margin band escalate to the model")
  void ambiguousPredictionSetEscalates() {
    // Two routes above the threshold, 0.01 apart: a tie, not a winner.
    when(embedding.classify("write a script"))
        .thenReturn(routed(ModelTier.CLOUD_ENTRY, 0.71, 0.70, 0.20));
    when(llm.classify("write a script")).thenReturn(new ClassificationOutcome(
        ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.Llm("multi-step", "qwen2.5:1.5b")));

    ClassificationOutcome outcome = cascade(calibrated()).classify("write a script");

    ClassificationJustification.Cascade justification = cascadeOf(outcome);
    assertEquals(CascadeLevel.LLM, justification.level());
    assertEquals(ModelTier.CLOUD_PREMIUM, outcome.tier());
    assertEquals(ClassificationStrategy.LLM, outcome.justification().strategy());
    assertEquals(DecisionReason.AMBIGUOUS_ESCALATED,
        DecisionReason.from(outcome.justification()));
    assertInstanceOf(ClassificationJustification.Embedding.class,
        justification.escalatedOn(),
        "the scores that justified the model call must ride along");
  }

  @Test
  @DisplayName("several tiers with a clear winner do not escalate")
  void wideMarginDecidesWithoutTheModel() {
    // Both above the threshold, but 0.20 apart: the set is not a tie.
    when(embedding.classify("summarize this"))
        .thenReturn(routed(ModelTier.CLOUD_ENTRY, 0.90, 0.70, 0.20));

    ClassificationOutcome outcome = cascade(calibrated()).classify("summarize this");

    assertEquals(CascadeLevel.EMBEDDING, cascadeOf(outcome).level());
    verify(llm, never()).classify(anyString());
  }

  @Test
  @DisplayName("an empty prediction set escalates rather than settle for keywords")
  void emptyPredictionSetEscalates() {
    when(embedding.classify("hmm")).thenReturn(handedOver(0.30, 0.25));
    when(llm.classify("hmm")).thenReturn(new ClassificationOutcome(
        ModelTier.LOCAL, new ClassificationJustification.Llm("trivial", "m")));

    ClassificationOutcome outcome = cascade(calibrated()).classify("hmm");

    assertEquals(CascadeLevel.LLM, cascadeOf(outcome).level());
    assertEquals(ModelTier.LOCAL, outcome.tier());
  }

  @Test
  @DisplayName("an embedding outage is not an ambiguity: no model call is bought")
  void embeddingFailureDoesNotEscalate() {
    // No evidence at all: the embedding level never produced scores.
    when(embedding.classify("hello")).thenReturn(new ClassificationOutcome(
        ModelTier.LOCAL,
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING,
            ClassificationJustification.FallbackCause.EMBEDDING_ERROR,
            ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT))));

    ClassificationOutcome outcome = cascade(calibrated()).classify("hello");

    assertEquals(CascadeLevel.EMBEDDING, cascadeOf(outcome).level());
    assertEquals(DecisionReason.ERROR_FALLBACK,
        DecisionReason.from(outcome.justification()),
        "a degraded decision must not read as a successful escalation");
    verify(llm, never()).classify(anyString());
  }

  @Test
  @DisplayName("without a calibration the cascade still runs, on the fixed band")
  void uncalibratedCascadeUsesTheFixedThreshold() {
    when(embedding.classify("write a script"))
        .thenReturn(routed(ModelTier.CLOUD_ENTRY, 0.71, 0.70, 0.20));
    when(llm.classify("write a script")).thenReturn(new ClassificationOutcome(
        ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.Llm("multi-step", "m")));

    ClassificationOutcome outcome = cascade(CalibrationFixtures.none(THRESHOLD))
        .classify("write a script");

    assertEquals(CascadeLevel.LLM, cascadeOf(outcome).level());
  }

  @Test
  @DisplayName("a model that fails after an escalation keeps its own reason")
  void escalatingOntoAFailingModelIsNotReportedAsASuccess() {
    when(embedding.classify("write a script"))
        .thenReturn(routed(ModelTier.CLOUD_ENTRY, 0.71, 0.70, 0.20));
    when(llm.classify("write a script")).thenReturn(new ClassificationOutcome(
        ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.Fallback(
            ClassificationStrategy.LLM,
            ClassificationJustification.FallbackCause.LLM_ERROR,
            ClassificationJustification.Heuristic.keyword("script"))));

    ClassificationOutcome outcome = cascade(calibrated()).classify("write a script");

    assertEquals(CascadeLevel.LLM, cascadeOf(outcome).level());
    assertEquals(DecisionReason.ERROR_FALLBACK,
        DecisionReason.from(outcome.justification()));
  }

  @Test
  @DisplayName("the band is read per call, so an edit applies to the next request")
  void marginBandIsHotConfigurable() {
    when(embedding.classify("write a script"))
        .thenReturn(routed(ModelTier.CLOUD_ENTRY, 0.71, 0.70, 0.20));
    CascadeComplexityClassifier cascade = cascade(calibrated());

    properties.setCascadeMarginBand(0.0);
    assertEquals(CascadeLevel.EMBEDDING,
        cascadeOf(cascade.classify("write a script")).level());

    properties.setCascadeMarginBand(0.05);
    when(llm.classify("write a script")).thenReturn(new ClassificationOutcome(
        ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.Llm("multi-step", "m")));
    assertEquals(CascadeLevel.LLM,
        cascadeOf(cascade.classify("write a script")).level());
  }

  @Test
  @DisplayName("the justification of the level that decided is kept verbatim")
  void innerJustificationIsNotReinterpreted() {
    ClassificationJustification reported =
        new ClassificationJustification.Llm("reason", "model-x");
    when(embedding.classify("x")).thenReturn(handedOver(0.30, 0.25));
    when(llm.classify("x"))
        .thenReturn(new ClassificationOutcome(ModelTier.LOCAL, reported));

    ClassificationOutcome outcome = cascade(calibrated()).classify("x");

    assertSame(reported, cascadeOf(outcome).decided());
  }

  private CascadeComplexityClassifier cascade(CalibrationUseCase calibrations) {
    return new CascadeComplexityClassifier(properties,
        new HeuristicComplexityClassifier(properties), embedding, llm,
        calibrations);
  }

  private static CalibrationUseCase calibrated() {
    return CalibrationFixtures.applied(
        CalibrationFixtures.calibration(CalibrationTarget.ROUTING, THRESHOLD),
        THRESHOLD);
  }

  /** An embedding decision whose three routes scored {@code scores}. */
  private static ClassificationOutcome routed(ModelTier tier, double... scores) {
    ClassificationJustification.Embedding embedding = scores(scores);
    return new ClassificationOutcome(tier, embedding);
  }

  /** A below-threshold hand-over: the heuristic decided, the scores ride along. */
  private static ClassificationOutcome handedOver(double... scores) {
    return new ClassificationOutcome(ModelTier.LOCAL,
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING,
            ClassificationJustification.FallbackCause.BELOW_THRESHOLD,
            ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT),
            scores(scores)));
  }

  private static ClassificationJustification.Embedding scores(double... scores) {
    ModelTier[] tiers = ModelTier.values();
    List<RouteCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < scores.length; i++) {
      candidates.add(new RouteCandidate("route-" + i,
          tiers[i % tiers.length], "example", scores[i], i + 1));
    }
    double margin = scores.length > 1 ? scores[0] - scores[1] : 0.0;
    return new ClassificationJustification.Embedding(
        candidates, scores[0], margin, THRESHOLD);
  }

  private static ClassificationJustification.Cascade cascadeOf(
      ClassificationOutcome outcome) {
    return assertInstanceOf(ClassificationJustification.Cascade.class,
        outcome.justification());
  }
}
