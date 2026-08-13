package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.CalibrationFixtures;
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
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/**
 * Client pinning (v2 batch 4, D3): a client that names a registered model id
 * gets it, unclassified.
 *
 * <p>Its own test class because it is the one path through {@code RoutingAdvisor}
 * where no classification happens at all — including in the trace, where the
 * justification is null and {@code CLIENT_PINNED} is the entire reason.
 */
@ExtendWith(MockitoExtension.class)
class RoutingAdvisorPinningTest {

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

  // ---- Client pinning (v2 batch 4, D3) ----

  @Test
  void aRegisteredModelIdIsHonouredWithoutClassifying() {
    ChatClientRequest request = buildRequest("Hello", "claude-haiku-4-20250506");
    when(modelRegistry.findByModelId("claude-haiku-4-20250506"))
        .thenReturn(Optional.of(entryModel()));
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    ChatClientResponse result = advisor.adviseCall(request, callChain);

    assertSame(chainResponse, result);
    verify(callChain).nextCall(request);
    verify(classifier, never()).classify(any());
  }

  @Test
  void aPinnedRequestIsTracedAsPinned() {
    ChatClientRequest request = buildRequest("Hello", "claude-haiku-4-20250506");
    when(modelRegistry.findByModelId(any()))
        .thenReturn(Optional.of(entryModel()));
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    RoutingDecision decision = capturedDecision();
    assertEquals(DecisionReason.CLIENT_PINNED, decision.decisionReason());
    assertEquals("claude-haiku-4-20250506", decision.chosenModelId());
    assertEquals(ModelTier.CLOUD_ENTRY, decision.chosenTier());
    assertNull(decision.justification(),
        "no classifier ran: the model id is the whole explanation");
    assertNull(decision.effectiveStrategy());
    assertEquals(ClassificationStrategy.EMBEDDING, decision.strategy(),
        "the configured strategy is still worth recording: it is what was skipped");
  }

  @Test
  void anUnregisteredModelIdIsStillRouted() {
    // Honouring it would send the request to a provider that does not exist.
    ChatClientRequest request = buildRequest("Refactor this", "gpt-9-turbo");
    when(modelRegistry.findByModelId("gpt-9-turbo")).thenReturn(Optional.empty());
    when(classifier.classify(any())).thenReturn(outcome(ModelTier.CLOUD_PREMIUM));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(premiumModel()));
    when(callChain.nextCall(any())).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(callChain).nextCall(requestCaptor.capture());
    assertEquals("claude-sonnet-4-20250514",
        requestCaptor.getValue().prompt().getOptions().getModel());
  }

  @Test
  void pinningCanBeTurnedOffToMakeRoutingMandatory() {
    properties.setClientPinning(false);
    ChatClientRequest request = buildRequest("Hello", "claude-haiku-4-20250506");
    when(classifier.classify("Hello")).thenReturn(outcome(ModelTier.LOCAL));
    when(modelRegistry.findByTier(ModelTier.LOCAL)).thenReturn(List.of());
    when(callChain.nextCall(request)).thenReturn(chainResponse);

    advisor.adviseCall(request, callChain);

    verify(classifier).classify("Hello");
    assertEquals(DecisionReason.NO_MODEL_FOR_TIER,
        capturedDecision().decisionReason());
  }

  @Test
  void streamingHonoursAPinToo() {
    ChatClientRequest request = buildRequest("Hello", "claude-haiku-4-20250506");
    Flux<ChatClientResponse> flux = Flux.just(chainResponse);
    when(modelRegistry.findByModelId(any()))
        .thenReturn(Optional.of(entryModel()));
    when(streamChain.nextStream(request)).thenReturn(flux);

    assertSame(flux, advisor.adviseStream(request, streamChain));
    verify(classifier, never()).classify(any());
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
