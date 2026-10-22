package io.github.yourimartin.gatewai.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.github.yourimartin.gatewai.domain.model.carbon.CarbonCalculator;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.decision.FailoverEvent;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughRequest;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.SamplingParameters;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException.Kind;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverCause;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.in.PassThroughStream;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EgressHealth;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.PassThroughClient;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;
import io.github.yourimartin.gatewai.domain.port.out.UpstreamEventStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Failover inside a tier on the pass-through path (v4 B.5, ADR 0017). */
@ExtendWith(MockitoExtension.class)
class PassThroughServiceFailoverTest {

  private static final ModelDefinition A =
      new ModelDefinition("a", "openai", "gpt-a", ModelPricing.blended(0.01), null, ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition B =
      new ModelDefinition("b", "anthropic", "claude-b", ModelPricing.blended(0.01), null, ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition C =
      new ModelDefinition("c", "vllm", "llama-c", ModelPricing.NONE, null, ModelTier.CLOUD_PREMIUM);
  private static final String BODY = "{\"model\":\"auto\"}";

  @Mock
  private PassThroughClient client;
  @Mock
  private ModelRegistry modelRegistry;
  @Mock
  private DecisionRecorder decisionRecorder;
  @Mock
  private DecisionMetricsRecorder decisionMetrics;
  @Mock
  private RequestLogRepository requestLogRepository;
  @Mock
  private CarbonIntensityProvider carbonIntensityProvider;
  @Mock
  private MetricsRecorder metricsRecorder;
  @Mock
  private ProviderRegions providerRegions;
  @Mock
  private CloudRegionZones cloudRegionZones;
  @Mock
  private EgressHealth health;

  @BeforeEach
  void registry() {
    lenient().when(health.admit(any())).thenReturn(true);
    lenient().when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM)).thenReturn(List.of(A, B, C));
    lenient().when(modelRegistry.findByModelId(anyString())).thenReturn(Optional.empty());
    lenient().when(modelRegistry.findByModelId("gpt-a")).thenReturn(Optional.of(A));
    lenient().when(modelRegistry.findByModelId("claude-b")).thenReturn(Optional.of(B));
    lenient().when(client.ignoredFeatures(any())).thenReturn(Set.of());
  }

  static Stream<Arguments> triggers() {
    return Stream.of(
        Arguments.of(new UpstreamFailureException(Kind.UNAVAILABLE, 0, "unreachable"),
            FailoverCause.CONNECTION_FAILED),
        Arguments.of(new UpstreamFailureException(Kind.TIMEOUT, 0, "timed out"),
            FailoverCause.TIMEOUT),
        Arguments.of(UpstreamFailureException.forStatus(429, "gpt-a"), FailoverCause.RATE_LIMITED),
        Arguments.of(UpstreamFailureException.forStatus(503, "gpt-a"),
            FailoverCause.SERVER_ERROR));
  }

  @ParameterizedTest
  @MethodSource("triggers")
  void aDefaultModelRequestFailsOverOnEachTrigger(UpstreamFailureException failure,
                                                  FailoverCause cause) {
    when(client.forward(A, BODY)).thenThrow(failure);
    when(client.forward(B, BODY)).thenReturn(ok(B));

    PassThroughResponse response = service(false).forward(request("auto"));

    assertThat(response.model()).isEqualTo("claude-b");
    FailoverEvent event = failover();
    assertThat(event.path()).isEqualTo(FailoverEvent.Path.PASS_THROUGH);
    assertThat(event.fromModelId()).isEqualTo("gpt-a");
    assertThat(event.toModelId()).isEqualTo("claude-b");
    assertThat(event.cause()).isEqualTo(cause);
    verify(health).failed(A);
    verify(health).succeeded(B);
  }

  @ParameterizedTest
  @MethodSource("triggers")
  void aStreamFailsOverWhileItIsBeingOpened(UpstreamFailureException failure,
                                            FailoverCause cause) {
    UpstreamEventStream upstream = new FixedUpstream();
    when(client.openStream(A, BODY)).thenThrow(failure);
    when(client.openStream(B, BODY)).thenReturn(upstream);

    PassThroughStream stream = service(false).openStream(request("auto"));
    stream.relay(event -> { });

    assertThat(stream.model()).isEqualTo("claude-b");
    assertThat(failover().cause()).isEqualTo(cause);
    assertThat(accounted().model()).isEqualTo("claude-b");
  }

  @Test
  void aStreamThatFailsAfterItOpenedDoesNotFailOver() {
    UpstreamFailureException midStream = new UpstreamFailureException(Kind.UNAVAILABLE, 0, "reset");
    when(client.openStream(A, BODY)).thenReturn(new FixedUpstream() {
      @Override
      public TokenUsage relay(Consumer<String> onEvent) {
        throw midStream;
      }
    });

    PassThroughStream stream = service(false).openStream(request("auto"));

    assertThatThrownBy(() -> stream.relay(event -> { })).isSameAs(midStream);
    verify(client, never()).openStream(eq(B), anyString());
    verify(decisionRecorder, never()).record(any(FailoverEvent.class));
  }

  @Test
  void theAnsweringModelIsTheOneAccounted() {
    when(client.forward(A, BODY)).thenThrow(UpstreamFailureException.forStatus(502, "gpt-a"));
    when(client.forward(B, BODY)).thenReturn(ok(B));

    service(false).forward(request("auto"));

    assertThat(accounted().model()).isEqualTo("claude-b");
  }

  @Test
  void anotherClientErrorIsReturnedWithoutFailingOver() {
    UpstreamFailureException rejected = UpstreamFailureException.forStatus(400, "gpt-a");
    when(client.forward(A, BODY)).thenThrow(rejected);

    assertThatThrownBy(() -> service(false).forward(request("auto"))).isSameAs(rejected);
    verify(client, never()).forward(eq(B), anyString());
    verify(health, never()).failed(any());
  }

  @Test
  void aPinnedRequestDoesNotFailOverByDefault() {
    UpstreamFailureException failure = UpstreamFailureException.forStatus(503, "gpt-a");
    when(client.forward(A, BODY)).thenThrow(failure);

    assertThatThrownBy(() -> service(false).forward(request("gpt-a"))).isSameAs(failure);
    verify(client, never()).forward(eq(B), anyString());
    verify(decisionRecorder, never()).record(any(FailoverEvent.class));
    // Still news about that model.
    verify(health).failed(A);
  }

  @Test
  void aPinnedRequestFailsOverWhenTheOperatorSaysSo() {
    when(client.forward(A, BODY)).thenThrow(UpstreamFailureException.forStatus(503, "gpt-a"));
    when(client.forward(B, BODY)).thenReturn(ok(B));

    assertThat(service(true).forward(request("gpt-a")).model()).isEqualTo("claude-b");
  }

  @Test
  void aCandidateThatWouldIgnoreAMarkedFeatureIsSkipped() {
    when(client.ignoredFeatures(B)).thenReturn(Set.of(PassThroughFeature.RESPONSE_FORMAT));
    when(client.forward(A, BODY)).thenThrow(UpstreamFailureException.forStatus(503, "gpt-a"));
    when(client.forward(C, BODY)).thenReturn(ok(C));

    PassThroughResponse response = service(false).forward(
        request("auto", PassThroughFeature.RESPONSE_FORMAT));

    assertThat(response.model()).isEqualTo("llama-c");
    verify(client, never()).forward(eq(B), anyString());
  }

  @Test
  void anExhaustedChainReturnsTheLastFailure() {
    when(client.forward(A, BODY)).thenThrow(UpstreamFailureException.forStatus(503, "gpt-a"));
    when(client.forward(B, BODY)).thenThrow(UpstreamFailureException.forStatus(503, "claude-b"));
    UpstreamFailureException last = UpstreamFailureException.forStatus(429, "llama-c");
    when(client.forward(C, BODY)).thenThrow(last);

    assertThatThrownBy(() -> service(false).forward(request("auto"))).isSameAs(last);
    ArgumentCaptor<FailoverEvent> events = ArgumentCaptor.forClass(FailoverEvent.class);
    verify(decisionRecorder, times(3)).record(events.capture());
    assertThat(events.getAllValues()).extracting(FailoverEvent::toModelId)
        .containsExactly("claude-b", "llama-c", null);
  }

  @Test
  void aCoolingEntryIsSkipped() {
    when(health.admit(A)).thenReturn(false);
    when(client.forward(B, BODY)).thenReturn(ok(B));

    assertThat(service(false).forward(request("auto")).model()).isEqualTo("claude-b");
    verify(client, never()).forward(eq(A), anyString());
  }

  private FailoverEvent failover() {
    ArgumentCaptor<FailoverEvent> event = ArgumentCaptor.forClass(FailoverEvent.class);
    verify(decisionRecorder).record(event.capture());
    verify(decisionMetrics).record(event.getValue());
    return event.getValue();
  }

  private RequestLog accounted() {
    ArgumentCaptor<RequestLog> log = ArgumentCaptor.forClass(RequestLog.class);
    verify(requestLogRepository).save(log.capture());
    return log.getValue();
  }

  private PassThroughService service(boolean failoverPinned) {
    return new PassThroughService(client, modelRegistry, decisionRecorder, decisionMetrics,
        requestLogRepository, carbonIntensityProvider, new GreenAccountant(new CarbonCalculator(), "EUR"),
        metricsRecorder, providerRegions, cloudRegionZones, health, "", failoverPinned);
  }

  private static PassThroughResponse ok(ModelDefinition model) {
    return new PassThroughResponse(200, "{}", model.modelId(), new TokenUsage(3, 2, 5));
  }

  private static PassThroughRequest request(String model, PassThroughFeature... extra) {
    Set<PassThroughFeature> features = new HashSet<>(Set.of(extra));
    features.add(PassThroughFeature.TOOLS);
    LlmRequest request = new LlmRequest(model,
        List.of(new LlmMessage("user", "Weather in Paris?")), null, null, null, null,
        SamplingParameters.NONE, features);
    return new PassThroughRequest(request, BODY);
  }

  /** An upstream that has opened and ends at once with usage. */
  private static class FixedUpstream implements UpstreamEventStream {

    @Override
    public TokenUsage relay(Consumer<String> onEvent) {
      onEvent.accept("[DONE]");
      return new TokenUsage(3, 2, 5);
    }

    @Override
    public void cancel() {
    }
  }
}
