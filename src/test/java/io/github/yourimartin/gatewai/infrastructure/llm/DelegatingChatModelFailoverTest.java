package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.decision.FailoverEvent;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverCause;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

import reactor.core.publisher.Flux;

/** Failover inside a tier on the chain (v4 B.5, ADR 0017), against mocked providers. */
class DelegatingChatModelFailoverTest {

  private static final ModelDefinition A = model("model-a", "provider-a");
  private static final ModelDefinition B = model("model-b", "provider-b");

  private final ChatModel providerA = mock(ChatModel.class);
  private final ChatModel providerB = mock(ChatModel.class);
  private final ModelRegistry registry = mock(ModelRegistry.class);
  private final DecisionRecorder decisions = mock(DecisionRecorder.class);
  private final DecisionMetricsRecorder metrics = mock(DecisionMetricsRecorder.class);
  private final InMemoryEgressHealth health = new InMemoryEgressHealth(new FailoverProperties());
  private final DelegatingChatModel delegating = new DelegatingChatModel(
      new ProviderChatModels(Map.of(
          "provider-a", new ProviderChatModels.ProviderInstance(
              ProviderProperties.ProviderType.OPENAI_COMPATIBLE, providerA),
          "provider-b", new ProviderChatModels.ProviderInstance(
              ProviderProperties.ProviderType.OPENAI_COMPATIBLE, providerB))),
      registry, health, decisions, metrics);

  @BeforeEach
  void registry() {
    when(registry.findByModelId("model-a")).thenReturn(Optional.of(A));
    when(registry.findByModelId("model-b")).thenReturn(Optional.of(B));
    when(registry.findByTier(ModelTier.CLOUD_PREMIUM)).thenReturn(List.of(A, B));
  }

  static Stream<Arguments> triggers() {
    return Stream.of(
        Arguments.of(new ResourceAccessException("I/O", new ConnectException("refused")),
            FailoverCause.CONNECTION_FAILED),
        Arguments.of(new ResourceAccessException("I/O", new SocketTimeoutException("read")),
            FailoverCause.TIMEOUT),
        Arguments.of(new NonTransientAiException("429 - slow down"), FailoverCause.RATE_LIMITED),
        Arguments.of(new TransientAiException("503 - unavailable"), FailoverCause.SERVER_ERROR),
        Arguments.of(new TransientAiException("529 - overloaded"), FailoverCause.SERVER_ERROR));
  }

  @ParameterizedTest
  @MethodSource("triggers")
  void aRoutedCallFailsOverOnEachTrigger(RuntimeException thrown, FailoverCause cause) {
    when(providerA.call(any(Prompt.class))).thenThrow(thrown);
    when(providerB.call(any(Prompt.class))).thenReturn(answer("from b"));

    ChatResponse response = delegating.call(routed("model-a"));

    assertThat(response.getResult().getOutput().getText()).isEqualTo("from b");
    assertThat((String) response.getMetadata().get(
        DelegatingChatModel.ANSWERED_MODEL_METADATA_KEY)).isEqualTo("model-b");
    FailoverEvent event = recorded();
    assertThat(event.fromModelId()).isEqualTo("model-a");
    assertThat(event.toModelId()).isEqualTo("model-b");
    assertThat(event.cause()).isEqualTo(cause);
    assertThat(event.path()).isEqualTo(FailoverEvent.Path.CHAIN);
    assertThat(event.tier()).isEqualTo(ModelTier.CLOUD_PREMIUM);
  }

  @Test
  void theNextEntryReceivesItsOwnModelId() {
    when(providerA.call(any(Prompt.class))).thenThrow(new TransientAiException("503 - x"));
    when(providerB.call(any(Prompt.class))).thenReturn(answer("from b"));

    delegating.call(routed("model-a"));

    ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
    verify(providerB).call(sent.capture());
    assertThat(sent.getValue().getOptions().getModel()).isEqualTo("model-b");
  }

  @Test
  void anotherClientErrorReachesTheClientWithoutFailingOver() {
    when(providerA.call(any(Prompt.class))).thenThrow(new NonTransientAiException("400 - bad"));

    assertThatThrownBy(() -> delegating.call(routed("model-a")))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.kind()).isEqualTo(UpstreamFailureException.Kind.REJECTED));
    verify(providerB, never()).call(any(Prompt.class));
    verify(decisions, never()).record(any(FailoverEvent.class));
  }

  @Test
  void aRequestTheRouterDidNotFlagNeverFailsOver() {
    when(providerA.call(any(Prompt.class))).thenThrow(new TransientAiException("503 - x"));

    assertThatThrownBy(() -> delegating.call(pinned("model-a")))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.upstreamStatus()).isEqualTo(503));
    verify(providerB, never()).call(any(Prompt.class));
    verify(decisions, never()).record(any(FailoverEvent.class));
  }

  @Test
  void anExhaustedChainReturnsTheLastFailureAndSaysNothingWasLeft() {
    when(providerA.call(any(Prompt.class))).thenThrow(new TransientAiException("503 - x"));
    when(providerB.call(any(Prompt.class))).thenThrow(new NonTransientAiException("429 - x"));

    assertThatThrownBy(() -> delegating.call(routed("model-a")))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.kind()).isEqualTo(UpstreamFailureException.Kind.RATE_LIMITED));
    ArgumentCaptor<FailoverEvent> events = ArgumentCaptor.forClass(FailoverEvent.class);
    verify(decisions, times(2)).record(events.capture());
    assertThat(events.getAllValues().get(1).fromModelId()).isEqualTo("model-b");
    assertThat(events.getAllValues().get(1).toModelId()).isNull();
  }

  @Test
  void anythingThatIsNotAnUpstreamFailureIsRethrownAsItIs() {
    IllegalStateException bug = new IllegalStateException("bug");
    when(providerA.call(any(Prompt.class))).thenThrow(bug);

    assertThatThrownBy(() -> delegating.call(routed("model-a"))).isSameAs(bug);
    verify(providerB, never()).call(any(Prompt.class));
  }

  @Test
  void aCoolingEntryIsSkippedThenProbedOnceTheCooldownEnds() {
    for (int i = 0; i < 3; i++) {
      health.failed(A);
    }
    when(providerB.call(any(Prompt.class))).thenReturn(answer("from b"));

    delegating.call(routed("model-a"));

    verify(providerA, never()).call(any(Prompt.class));
  }

  @Test
  void aPinnedRequestCountsAgainstTheModelsHealthToo() {
    when(providerA.call(any(Prompt.class))).thenThrow(new TransientAiException("503 - x"));
    for (int i = 0; i < 3; i++) {
      assertThatThrownBy(() -> delegating.call(pinned("model-a")))
          .isInstanceOf(UpstreamFailureException.class);
    }

    assertThat(health.admit(A)).isFalse();
  }

  @Test
  void aStreamFailsOverBeforeItsFirstChunk() {
    when(providerA.stream(any(Prompt.class)))
        .thenReturn(Flux.error(new TransientAiException("503 - x")));
    when(providerB.stream(any(Prompt.class)))
        .thenReturn(Flux.just(answer("from "), answer("b")));

    List<ChatResponse> chunks = delegating.stream(routed("model-a")).collectList().block();

    assertThat(chunks).extracting(r -> r.getResult().getOutput().getText())
        .containsExactly("from ", "b");
    assertThat(chunks).allSatisfy(r -> assertThat((String) r.getMetadata()
        .get(DelegatingChatModel.ANSWERED_MODEL_METADATA_KEY)).isEqualTo("model-b"));
    assertThat(recorded().cause()).isEqualTo(FailoverCause.SERVER_ERROR);
  }

  @Test
  void aStreamNeverFailsOverAfterItsFirstChunk() {
    TransientAiException midStream = new TransientAiException("503 - x");
    when(providerA.stream(any(Prompt.class)))
        .thenReturn(Flux.concat(Flux.just(answer("half")), Flux.error(midStream)));

    assertThatThrownBy(() -> delegating.stream(routed("model-a")).collectList().block())
        .isSameAs(midStream);
    verify(providerB, never()).stream(any(Prompt.class));
    verify(decisions, never()).record(any(FailoverEvent.class));
  }

  @Test
  void anAnswerFromTheFirstTargetIsNotStamped() {
    when(providerA.call(any(Prompt.class))).thenReturn(answer("from a"));

    ChatResponse response = delegating.call(routed("model-a"));

    assertThat((Object) response.getMetadata().get(
        DelegatingChatModel.ANSWERED_MODEL_METADATA_KEY)).isNull();
  }

  private FailoverEvent recorded() {
    ArgumentCaptor<FailoverEvent> event = ArgumentCaptor.forClass(FailoverEvent.class);
    verify(decisions).record(event.capture());
    verify(metrics).record(event.getValue());
    return event.getValue();
  }

  private static Prompt routed(String modelId) {
    return new Prompt("hello", GatewaiChatOptions.builder().model(modelId).failover(true).build());
  }

  private static Prompt pinned(String modelId) {
    return new Prompt("hello", ChatOptions.builder().model(modelId).build());
  }

  private static ChatResponse answer(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static ModelDefinition model(String id, String provider) {
    return new ModelDefinition(id, provider, id, ModelPricing.NONE, EnergyProfile.NOT_ACCOUNTED,
        ModelTier.CLOUD_PREMIUM);
  }
}
