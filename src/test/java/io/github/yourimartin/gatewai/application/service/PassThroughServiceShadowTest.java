package io.github.yourimartin.gatewai.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.yourimartin.gatewai.domain.model.carbon.CarbonCalculator;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughRequest;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.llm.SamplingParameters;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EgressHealth;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.PassThroughClient;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RelayedAnswerReader;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;
import io.github.yourimartin.gatewai.domain.port.out.UpstreamEventStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The pass-through path serving a shadow client (v4 D.1). */
@ExtendWith(MockitoExtension.class)
class PassThroughServiceShadowTest {

  private static final ModelDefinition LOCAL =
      new ModelDefinition("local", "ollama", "qwen2.5:0.5b", ModelPricing.NONE, null,
          ModelTier.LOCAL);
  private static final ModelDefinition PREMIUM =
      new ModelDefinition("premium", "openai", "gpt-5", ModelPricing.blended(0.01), null,
          ModelTier.CLOUD_PREMIUM);
  private static final String BODY = "{\"model\":\"auto\"}";
  private static final RequestContext SHADOW =
      new RequestContext("client-7", "trace-42", ClientMode.SHADOW);
  private static final RelayedAnswer ANSWER =
      new RelayedAnswer("gpt-5", "Hello.", "stop", new TokenUsage(30, 10, 40));

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
  @Mock
  private ShadowEvaluator shadow;
  @Mock
  private RelayedAnswerReader answers;

  @BeforeEach
  void registry() {
    lenient().when(health.admit(any())).thenReturn(true);
    lenient().when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM)).thenReturn(List.of(PREMIUM));
    lenient().when(modelRegistry.findByModelId(anyString())).thenReturn(Optional.empty());
    lenient().when(modelRegistry.findByModelId("gpt-5")).thenReturn(Optional.of(PREMIUM));
    lenient().when(modelRegistry.findByModelId("qwen2.5:0.5b")).thenReturn(Optional.of(LOCAL));
    lenient().when(client.ignoredFeatures(any())).thenReturn(Set.of());
    lenient().when(client.forward(any(), anyString())).thenAnswer(call -> new PassThroughResponse(
        200, "{}", call.<ModelDefinition>getArgument(0).modelId(), new TokenUsage(30, 10, 40)));
    lenient().when(answers.read(any())).thenReturn(Optional.of(ANSWER));
  }

  @Test
  void aRequestTheChainCouldHonourIsServedThenEvaluatedWithNoDecisionOfItsOwn() {
    PassThroughRequest request = PassThroughRequest.shadow(plain("auto"), BODY);

    PassThroughResponse response = inShadow(() -> service().forward(request));

    assertThat(response.model()).isEqualTo("gpt-5");
    verify(client).forward(PREMIUM, BODY);
    verify(requestLogRepository).save(any());
    verify(shadow).submit(SHADOW, request.request(), Optional.of(ANSWER));
    // The verdict is the chain's, recorded by the evaluation: none here.
    verify(decisionRecorder, never()).record(any(CacheDecision.class));
    verify(decisionRecorder, never()).record(any(RoutingDecision.class));
  }

  @Test
  void aRegisteredModelIsServedAsNamed() {
    inShadow(() -> service().forward(PassThroughRequest.shadow(plain("qwen2.5:0.5b"), BODY)));

    verify(client).forward(LOCAL, BODY);
  }

  @Test
  void anUnregisteredModelIsRefusedRatherThanSentToTheDefault() {
    assertThatThrownBy(() -> inShadow(() ->
        service().forward(PassThroughRequest.shadow(plain("gpt-4-typo"), BODY))))
        .isInstanceOf(UnknownModelException.class)
        .hasMessageContaining("gpt-4-typo")
        .hasMessageContaining("'auto'");
    verifyNoInteractions(client, shadow);
  }

  @Test
  void aRequestTheChainCannotHonourIsTracedAsAPassThroughAndCountedAsABypass() {
    LlmRequest tools = new LlmRequest("auto", List.of(new LlmMessage("user", "Weather?")), null,
        null, null, null, SamplingParameters.NONE, Set.of(PassThroughFeature.TOOLS));

    inShadow(() -> service().forward(PassThroughRequest.shadow(tools, BODY)));

    ArgumentCaptor<CacheDecision> cache = ArgumentCaptor.forClass(CacheDecision.class);
    verify(decisionRecorder).record(cache.capture());
    assertThat(cache.getValue().mode()).isEqualTo(ClientMode.SHADOW);
    ArgumentCaptor<RoutingDecision> routing = ArgumentCaptor.forClass(RoutingDecision.class);
    verify(decisionRecorder).record(routing.capture());
    assertThat(routing.getValue().mode()).isEqualTo(ClientMode.SHADOW);
    verify(shadow).bypassed();
    verify(shadow, never()).submit(any(), any(), any());
  }

  @Test
  void aStreamIsRelayedFirstThenReadBackForTheEvaluation() {
    List<String> order = new ArrayList<>();
    RelayedAnswerReader.StreamedAnswer streamed = new RelayedAnswerReader.StreamedAnswer() {
      @Override
      public void accept(String event) {
        order.add("read:" + event);
      }

      @Override
      public Optional<RelayedAnswer> answer(TokenUsage usage) {
        return Optional.of(ANSWER);
      }
    };
    when(answers.streamed("gpt-5")).thenReturn(streamed);
    when(client.openStream(eq(PREMIUM), anyString())).thenReturn(new UpstreamEventStream() {
      @Override
      public TokenUsage relay(Consumer<String> onEvent) {
        onEvent.accept("a");
        return new TokenUsage(1, 1, 2);
      }

      @Override
      public void cancel() {
        // nothing to close
      }
    });
    PassThroughRequest request = PassThroughRequest.shadow(plain("auto"), BODY);

    inShadow(() -> {
      service().openStream(request).relay(event -> order.add("client:" + event));
      return null;
    });

    assertThat(order).containsExactly("client:a", "read:a");
    verify(shadow).submit(SHADOW, request.request(), Optional.of(ANSWER));
  }

  @Test
  void anEnforcedPassThroughIsNeverEvaluated() {
    LlmRequest tools = new LlmRequest("auto", List.of(new LlmMessage("user", "Weather?")), null,
        null, null, null, SamplingParameters.NONE, Set.of(PassThroughFeature.TOOLS));

    service().forward(new PassThroughRequest(tools, BODY));

    verifyNoInteractions(shadow, answers);
  }

  private static LlmRequest plain(String model) {
    return new LlmRequest(model, List.of(new LlmMessage("user", "Hi")), null, null);
  }

  private static <T> T inShadow(Supplier<T> call) {
    return ScopedValue.where(RequestContext.CURRENT, SHADOW).call(call::get);
  }

  private PassThroughService service() {
    return new PassThroughService(client, modelRegistry, decisionRecorder, decisionMetrics,
        requestLogRepository, carbonIntensityProvider,
        new GreenAccountant(new CarbonCalculator(), "EUR"), metricsRecorder, providerRegions,
        cloudRegionZones, health, shadow, answers, "", false);
  }
}
