package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;

import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * What the router records about a cascade decision (v2 batch 4): how far it
 * went, and that the cascade — not the level that happened to decide — is what
 * was configured.
 */
@ExtendWith(MockitoExtension.class)
class RoutingAdvisorCascadeTest {

  @Mock
  private ComplexityClassifier classifier;

  @Mock
  private ModelRegistry modelRegistry;

  @Mock
  private DecisionRecorder decisionRecorder;

  @Mock
  private DecisionMetricsRecorder decisionMetrics;

  @Mock
  private RoutingConfigVersionTracker configVersion;

  @Mock
  private CallAdvisorChain callChain;

  @Mock
  private ChatClientResponse chainResponse;

  @Captor
  private ArgumentCaptor<ChatClientRequest> requestCaptor;

  private ClassifierProperties properties;
  private RoutingAdvisor advisor;

  @BeforeEach
  void setUp() {
    properties = new ClassifierProperties();
    advisor = new RoutingAdvisor(classifier, modelRegistry,
        decisionRecorder, decisionMetrics, configVersion, CalibrationFixtures.none(0.60),
        properties);
  }

  // ---- Cascade tracing (v2 batch 4) ----

  @Test
  void recordsHowFarTheCascadeWent() {
    ChatClientRequest request = buildRequest("write a script");
    when(classifier.classify(any())).thenReturn(new ClassificationOutcome(
        ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.Cascade(CascadeLevel.LLM, 0.02,
            new ClassificationJustification.Llm("multi-step", "qwen2.5:1.5b"),
            routeScores())));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    RoutingDecision decision = capturedDecision();
    assertEquals(CascadeLevel.LLM, decision.escalatedTo());
    assertEquals(DecisionReason.AMBIGUOUS_ESCALATED, decision.decisionReason());
    assertEquals(ClassificationStrategy.CASCADE, decision.strategy(),
        "the cascade is what was configured");
    assertEquals(ClassificationStrategy.LLM, decision.effectiveStrategy(),
        "the model is what decided");
  }

  @Test
  void aNonCascadeDecisionHasNoLevel() {
    ChatClientRequest request = buildRequest("Refactor this class");
    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    assertNull(capturedDecision().escalatedTo());
  }

  @Test
  void theRecordedPredictionSetIsTheOneTheCascadeEscalatedOn() {
    ChatClientRequest request = buildRequest("write a script");
    advisor = new RoutingAdvisor(classifier, modelRegistry, decisionRecorder,
        decisionMetrics, configVersion,
        CalibrationFixtures.applied(CalibrationFixtures.calibration(
            CalibrationTarget.ROUTING, 0.60), 0.60),
        properties);
    when(classifier.classify(any())).thenReturn(new ClassificationOutcome(
        ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.Cascade(CascadeLevel.LLM, 0.02,
            new ClassificationJustification.Llm("multi-step", "m"),
            routeScores())));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    assertEquals(List.of(ModelTier.LOCAL, ModelTier.CLOUD_ENTRY),
        capturedDecision().conformalSet());
  }

  /** Two routes above 0.60, 0.01 apart: what an escalation looks like. */
  private static ClassificationJustification.Embedding routeScores() {
    return new ClassificationJustification.Embedding(List.of(
        new ClassificationJustification.RouteCandidate(
            "casual-chat", ModelTier.LOCAL, "hello", 0.71, 1),
        new ClassificationJustification.RouteCandidate(
            "drafting", ModelTier.CLOUD_ENTRY, "summarize", 0.70, 2)),
        0.71, 0.01, 0.60);
  }

  private RoutingDecision capturedDecision() {
    ArgumentCaptor<RoutingDecision> captor =
        ArgumentCaptor.forClass(RoutingDecision.class);
    verify(decisionRecorder).record(captor.capture());
    return captor.getValue();
  }

  private static ChatClientRequest buildRequest(String userText) {
    return buildRequest(userText, null);
  }

  /** A request that names a model, the way every OpenAI client does. */
  private static ChatClientRequest buildRequest(String userText, String model) {
    ChatOptions options = model == null
        ? null : ChatOptions.builder().model(model).build();
    return ChatClientRequest.builder()
        .prompt(new Prompt(List.of(new UserMessage(userText)), options))
        .context(Map.of())
        .build();
  }

  private static ModelDefinition premiumModel() {
    return new ModelDefinition(
        "claude-sonnet", "anthropic",
        "claude-sonnet-4-20250514", 0.015, 0.6,
        ModelTier.CLOUD_PREMIUM);
  }

  private static ModelDefinition entryModel() {
    return new ModelDefinition(
        "claude-haiku", "anthropic",
        "claude-haiku-4-20250506", 0.002, 0.15,
        ModelTier.CLOUD_ENTRY);
  }
}
