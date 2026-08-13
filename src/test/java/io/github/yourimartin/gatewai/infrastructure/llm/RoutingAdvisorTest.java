package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;

import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.PromptHash;
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
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import reactor.core.publisher.Flux;

@ExtendWith(MockitoExtension.class)
class RoutingAdvisorTest {

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
  private StreamAdvisorChain streamChain;

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

  // ---- Routing tests ----

  @Test
  void routesToPremiumModelForComplexQuery() {
    ChatClientRequest request = buildRequest("Refactor this class");
    ModelDefinition sonnet = premiumModel();

    when(classifier.classify("Refactor this class"))
        .thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(sonnet));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    ChatClientRequest routed = requestCaptor.getValue();
    assertEquals("claude-sonnet-4-20250514",
        routed.prompt().getOptions().getModel());
  }

  @Test
  void routesToEntryModelForMediumQuery() {
    ChatClientRequest request = buildRequest(
        "x".repeat(150));
    ModelDefinition haiku = entryModel();

    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_ENTRY));
    when(modelRegistry.findByTier(ModelTier.CLOUD_ENTRY))
        .thenReturn(List.of(haiku));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    assertEquals("claude-haiku-4-20250506",
        requestCaptor.getValue().prompt().getOptions().getModel());
  }

  @Test
  void preservesOriginalOptionsExceptModel() {
    Prompt prompt = new Prompt(
        List.of(new UserMessage("Refactor this")),
        ChatOptions.builder()
            .model("original-model")
            .temperature(0.7)
            .maxTokens(256)
            .build()
    );
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();

    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    ChatOptions routedOptions =
        requestCaptor.getValue().prompt().getOptions();
    assertEquals("claude-sonnet-4-20250514", routedOptions.getModel());
    assertEquals(0.7, routedOptions.getTemperature());
    assertEquals(256, routedOptions.getMaxTokens());
  }

  // ---- Fallback tests ----

  @Test
  void fallsBackToDefaultWhenNoModelForTier() {
    ChatClientRequest request = buildRequest("Hello");

    when(classifier.classify("Hello")).thenReturn(outcome(ModelTier.LOCAL));
    when(modelRegistry.findByTier(ModelTier.LOCAL))
        .thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(request);
    assertSame(chainResponse, result);
  }

  // ---- Skip-routing tests ----

  @Test
  void blankTextSkipsRoutingAndPassesThrough() {
    ChatClientRequest request = buildRequest("   ");

    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(chainResponse, result);
  }

  @Test
  void noUserMessageSkipsRouting() {
    Prompt prompt = new Prompt(List.of());
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();

    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(chainResponse, result);
  }

  // ---- Context preservation ----

  @Test
  void preservesContextInRoutedRequest() {
    Map<String, Object> context = Map.of("key", "value");
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(new Prompt(new UserMessage("Refactor")))
        .context(context)
        .build();

    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    assertEquals(context, requestCaptor.getValue().context());
  }

  // ---- Stream / metadata tests ----

  @Test
  void adviseStreamPassesThrough() {
    ChatClientRequest request = buildRequest("stream test");
    Flux<ChatClientResponse> flux = Flux.just(chainResponse);
    // No model registered for the classified tier: the advisor passes through.
    when(classifier.classify("stream test")).thenReturn(outcome(ModelTier.LOCAL));
    when(streamChain.nextStream(request)).thenReturn(flux);

    Flux<ChatClientResponse> result =
        advisor.adviseStream(request, streamChain);

    assertSame(flux, result);
  }

  @Test
  void nameIsRouting() {
    assertEquals("Routing", advisor.getName());
  }

  @Test
  void orderIsAfterCache() {
    assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, advisor.getOrder());
  }

  // ---- Messages preservation ----

  @Test
  void preservesAllMessagesInRoutedPrompt() {
    Prompt prompt = new Prompt(List.of(
        new UserMessage("Refactor"),
        new UserMessage("this code")
    ));
    ChatClientRequest request = ChatClientRequest.builder()
        .prompt(prompt)
        .context(Map.of())
        .build();

    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    assertNotNull(requestCaptor.getValue().prompt().getInstructions());
    assertEquals(2,
        requestCaptor.getValue().prompt().getInstructions().size());
  }

  // ---- Decision tracing (v2 batch 2) ----

  @Test
  void recordsTheDecisionItTook() {
    ChatClientRequest request = buildRequest("Refactor this class");
    when(classifier.classify("Refactor this class"))
        .thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(configVersion.current()).thenReturn("cfg0000000000001");
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    RoutingDecision decision = capturedDecision();
    assertEquals(ModelTier.CLOUD_PREMIUM, decision.chosenTier());
    assertEquals("claude-sonnet-4-20250514", decision.chosenModelId());
    assertEquals(DecisionReason.MATCH, decision.decisionReason());
    assertEquals("cfg0000000000001", decision.routingConfigVersion());
    assertEquals(PromptHash.of("Refactor this class"), decision.promptHash());
    assertEquals("Refactor this class".length(), decision.promptLength());
    assertNotNull(decision.createdAt());
  }

  @Test
  void recordsTheConfiguredStrategyAlongsideTheOneThatDecided() {
    // A hand-over must stay visible: same tier, very different confidence.
    ChatClientRequest request = buildRequest("Refactor this class");
    when(classifier.classify(any())).thenReturn(new ClassificationOutcome(
        ModelTier.CLOUD_PREMIUM,
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING,
            ClassificationJustification.FallbackCause.EMBEDDING_ERROR,
            ClassificationJustification.Heuristic.keyword("refactor"))));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    RoutingDecision decision = capturedDecision();
    assertEquals(ClassificationStrategy.EMBEDDING, decision.strategy());
    assertEquals(ClassificationStrategy.HEURISTIC, decision.effectiveStrategy());
    assertEquals(DecisionReason.ERROR_FALLBACK, decision.decisionReason());
  }

  @Test
  void recordsABelowThresholdHandOverAsItsOwnReason() {
    ChatClientRequest request = buildRequest("ambiguous");
    when(classifier.classify(any())).thenReturn(new ClassificationOutcome(
        ModelTier.LOCAL,
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING,
            ClassificationJustification.FallbackCause.BELOW_THRESHOLD,
            ClassificationJustification.Heuristic.of(
                ClassificationJustification.HeuristicRule.DEFAULT))));
    when(modelRegistry.findByTier(ModelTier.LOCAL))
        .thenReturn(List.of(entryModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    assertEquals(DecisionReason.BELOW_THRESHOLD_FALLBACK,
        capturedDecision().decisionReason());
  }

  @Test
  void recordsAPassThroughWhenNoModelIsRegisteredForTheTier() {
    ChatClientRequest request = buildRequest("Hello");
    when(classifier.classify("Hello")).thenReturn(outcome(ModelTier.LOCAL));
    when(modelRegistry.findByTier(ModelTier.LOCAL)).thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    RoutingDecision decision = capturedDecision();
    assertEquals(DecisionReason.NO_MODEL_FOR_TIER, decision.decisionReason());
    assertNull(decision.chosenModelId());
    assertEquals(ModelTier.LOCAL, decision.chosenTier());
  }

  @Test
  void blankTextIsNotARoutingDecisionAtAll() {
    ChatClientRequest request = buildRequest("   ");
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(decisionRecorder, never()).record(any(RoutingDecision.class));
  }

  @Test
  void aFailingTracerNeverBreaksRouting() {
    ChatClientRequest request = buildRequest("Refactor this class");
    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(configVersion.current())
        .thenThrow(new IllegalStateException("config unreadable"));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(chainResponse, result);
    verify(callChain).nextCall(any());
  }

  @Test
  void streamingRecordsItsDecisionToo() {
    ChatClientRequest request = buildRequest("Refactor this class");
    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(streamChain.nextStream(any())).thenReturn(Flux.just(chainResponse));

    advisor.adviseStream(request, streamChain);

    assertEquals("claude-sonnet-4-20250514",
        capturedDecision().chosenModelId());
  }

  @Test
  void theTraceAndTheMetricsSeeTheSameDecision() {
    // One object, two sinks (v2 batch 6): an aggregate that disagreed with the
    // row behind it would be worse than no aggregate.
    ChatClientRequest request = buildRequest("Refactor this class");
    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    ArgumentCaptor<RoutingDecision> metered =
        ArgumentCaptor.forClass(RoutingDecision.class);
    verify(decisionMetrics).record(metered.capture());
    assertSame(capturedDecision(), metered.getValue());
  }

  private RoutingDecision capturedDecision() {
    ArgumentCaptor<RoutingDecision> captor =
        ArgumentCaptor.forClass(RoutingDecision.class);
    verify(decisionRecorder).record(captor.capture());
    return captor.getValue();
  }

  // ---- Helpers ----

  private static ChatClientRequest buildRequest(String userText) {
    Prompt prompt = new Prompt(new UserMessage(userText));
    return ChatClientRequest.builder()
        .prompt(prompt)
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
