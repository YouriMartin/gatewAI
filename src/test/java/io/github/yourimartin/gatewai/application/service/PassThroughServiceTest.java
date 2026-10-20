package io.github.yourimartin.gatewai.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.carbon.CarbonCalculator;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.PromptHash;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughRequest;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.SamplingParameters;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.model.llm.UnsupportedFeatureException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;
import io.github.yourimartin.gatewai.domain.model.routing.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.PassThroughClient;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PassThroughServiceTest {

  private static final ModelDefinition LOCAL =
      new ModelDefinition("local", "ollama", "qwen2.5:0.5b", 0.0, null, ModelTier.LOCAL);
  private static final ModelDefinition PREMIUM =
      new ModelDefinition("premium", "openai", "gpt-5", 0.01, null, ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition PREMIUM_2 =
      new ModelDefinition("premium2", "openai", "gpt-5-mini", 0.002, null, ModelTier.CLOUD_PREMIUM);
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

  @BeforeEach
  void registry() {
    lenient().when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(PREMIUM, PREMIUM_2));
    lenient().when(modelRegistry.findByModelId(anyString())).thenReturn(Optional.empty());
    lenient().when(modelRegistry.findByModelId("gpt-5")).thenReturn(Optional.of(PREMIUM));
    lenient().when(modelRegistry.findByModelId("qwen2.5:0.5b")).thenReturn(Optional.of(LOCAL));
    lenient().when(modelRegistry.allModels()).thenReturn(List.of(LOCAL, PREMIUM, PREMIUM_2));
    lenient().when(client.ignoredFeatures(any())).thenReturn(Set.of());
    lenient().when(client.forward(any(), anyString())).thenAnswer(call -> new PassThroughResponse(
        200, "{}", call.<ModelDefinition>getArgument(0).modelId(), new TokenUsage(30, 10, 40)));
  }

  @Test
  void anUnregisteredModelGoesToTheFirstPremiumEntryByDefault() {
    service("").forward(request("auto", PassThroughFeature.TOOLS));

    verify(client).forward(PREMIUM, BODY);
    RoutingDecision routing = routingDecision();
    assertThat(routing.decisionReason()).isEqualTo(DecisionReason.PASSTHROUGH_DEFAULT);
    assertThat(routing.chosenModelId()).isEqualTo("gpt-5");
    assertThat(routing.chosenTier()).isEqualTo(ModelTier.CLOUD_PREMIUM);
  }

  @Test
  void aConfiguredDefaultReplacesThePremiumEntry() {
    service("qwen2.5:0.5b").forward(request(null, PassThroughFeature.IMAGE_INPUT));

    verify(client).forward(LOCAL, BODY);
  }

  @Test
  void aRegisteredModelIsAPinAndIsNeverClassified() {
    service("").forward(request("qwen2.5:0.5b", PassThroughFeature.TOOLS));

    verify(client).forward(LOCAL, BODY);
    RoutingDecision routing = routingDecision();
    assertThat(routing.decisionReason()).isEqualTo(DecisionReason.PASSTHROUGH_PINNED);
    assertThat(routing.chosenTier()).isEqualTo(ModelTier.LOCAL);
    // Nothing was classified: the row has the shape of a CLIENT_PINNED one.
    assertThat(routing.justification()).isNull();
    assertThat(routing.effectiveStrategy()).isNull();
    assertThat(routing.conformalSet()).isNull();
  }

  @Test
  void aDefaultModelTheRegistryDoesNotHoldFailsStartup() {
    assertThatThrownBy(() -> service("gpt-typo"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gatewai.passthrough.default-model='gpt-typo'")
        .hasMessageContaining("qwen2.5:0.5b");
  }

  @Test
  void noPremiumEntryAndNoDefaultIsAnUnknownModel() {
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM)).thenReturn(List.of());

    PassThroughService service = service("");

    assertThatThrownBy(() -> service.forward(request("auto", PassThroughFeature.TOOLS)))
        .isInstanceOf(UnknownModelException.class)
        .hasMessageContaining("gatewai.passthrough.default-model");
  }

  @Test
  void theCacheDecisionIsABypassNamingTheFirstFeatureInDeclarationOrder() {
    service("").forward(request("auto", PassThroughFeature.LOGIT_BIAS,
        PassThroughFeature.RESPONSE_FORMAT));

    ArgumentCaptor<CacheDecision> cache = ArgumentCaptor.forClass(CacheDecision.class);
    verify(decisionRecorder).record(cache.capture());
    assertThat(cache.getValue().outcome()).isEqualTo(CacheOutcome.BYPASS);
    assertThat(cache.getValue().reason()).isEqualTo(CacheDecisionReason.PASSTHROUGH_RESPONSE_FORMAT);
    assertThat(cache.getValue().promptHash()).isEqualTo(PromptHash.of("Weather in Paris?"));
    verify(decisionMetrics).record(cache.getValue());
  }

  @Test
  void theServedRequestIsAccountedLikeTheChainUnderTheRegistryId() {
    RequestContext context = new RequestContext("client-7", "trace-42");

    ScopedValue.where(RequestContext.CURRENT, context).run(() ->
        service("").forward(request("auto", PassThroughFeature.TOOLS)));

    ArgumentCaptor<RequestLog> log = ArgumentCaptor.forClass(RequestLog.class);
    verify(requestLogRepository).save(log.capture());
    assertThat(log.getValue().model()).isEqualTo("gpt-5");
    assertThat(log.getValue().promptTokens()).isEqualTo(30);
    assertThat(log.getValue().completionTokens()).isEqualTo(10);
    assertThat(log.getValue().totalTokens()).isEqualTo(40);
    assertThat(log.getValue().cacheHit()).isFalse();
    assertThat(log.getValue().clientId()).isEqualTo("client-7");
    assertThat(log.getValue().correlationId()).isEqualTo("trace-42");
    assertThat(log.getValue().green()).isNotNull();
    verify(metricsRecorder).record(log.getValue());
    assertThat(routingDecision().correlationId()).isEqualTo("trace-42");
  }

  @Test
  void anUpstreamWithoutUsageIsLoggedWithZeroTokens() {
    doReturn(new PassThroughResponse(200, "{}", "gpt-5", null))
        .when(client).forward(any(), anyString());

    service("").forward(request("auto", PassThroughFeature.TOOLS));

    ArgumentCaptor<RequestLog> log = ArgumentCaptor.forClass(RequestLog.class);
    verify(requestLogRepository).save(log.capture());
    assertThat(log.getValue().totalTokens()).isZero();
  }

  @Test
  void aFeatureTheTargetWouldIgnoreIsRefusedBeforeAnythingIsSentOrTraced() {
    when(client.ignoredFeatures(PREMIUM)).thenReturn(
        Set.of(PassThroughFeature.RESPONSE_FORMAT, PassThroughFeature.LOGPROBS));
    when(client.describeEndpoint(PREMIUM)).thenReturn("the endpoint documents it as ignored");

    assertThatThrownBy(() -> service("").forward(
        request("auto", PassThroughFeature.TOOLS, PassThroughFeature.RESPONSE_FORMAT)))
        .isInstanceOfSatisfying(UnsupportedFeatureException.class, e -> {
          assertThat(e.features()).containsExactly(PassThroughFeature.RESPONSE_FORMAT);
          assertThat(e.getMessage()).contains("gpt-5").contains("documents it as ignored");
        });
    verify(client, never()).forward(any(), anyString());
    verifyNoInteractions(decisionRecorder, requestLogRepository);
  }

  @Test
  void anUpstreamFailureIsTracedButNotLogged() {
    doThrow(UpstreamFailureException.forStatus(503, "gpt-5"))
        .when(client).forward(any(), anyString());

    assertThatThrownBy(() -> service("").forward(request("auto", PassThroughFeature.TOOLS)))
        .isInstanceOf(UpstreamFailureException.class);
    routingDecision();
    verifyNoInteractions(requestLogRepository);
  }

  @Test
  void aFailingTraceNeverFailsTheRequest() {
    doThrow(new IllegalStateException("store down")).when(decisionRecorder)
        .record(any(CacheDecision.class));
    doThrow(new IllegalStateException("store down")).when(decisionRecorder)
        .record(any(RoutingDecision.class));

    PassThroughResponse response = service("").forward(request("auto", PassThroughFeature.TOOLS));

    assertThat(response.status()).isEqualTo(200);
    verify(requestLogRepository).save(any());
  }

  @Test
  void aRequestWhoseLastTurnsAreToolResultsHashesItsLastUserMessage() {
    LlmRequest request = new LlmRequest("auto", List.of(
        new LlmMessage("user", "Weather in Paris?"),
        new LlmMessage("assistant", ""),
        new LlmMessage("tool", "18 degrees")), null, null, null, null,
        SamplingParameters.NONE, Set.of(PassThroughFeature.TOOL_MESSAGES));

    service("").forward(new PassThroughRequest(request, BODY));

    RoutingDecision routing = routingDecision();
    assertThat(routing.promptHash()).isEqualTo(PromptHash.of("Weather in Paris?"));
    assertThat(routing.promptLength()).isEqualTo("Weather in Paris?".length());
  }

  private RoutingDecision routingDecision() {
    ArgumentCaptor<RoutingDecision> routing = ArgumentCaptor.forClass(RoutingDecision.class);
    verify(decisionRecorder).record(routing.capture());
    verify(decisionMetrics).record(eq(routing.getValue()));
    return routing.getValue();
  }

  private PassThroughService service(String defaultModel) {
    return new PassThroughService(client, modelRegistry, decisionRecorder, decisionMetrics,
        requestLogRepository, carbonIntensityProvider, new GreenAccountant(new CarbonCalculator()),
        metricsRecorder, providerRegions, cloudRegionZones, defaultModel);
  }

  private static PassThroughRequest request(String model, PassThroughFeature... features) {
    LlmRequest request = new LlmRequest(model,
        List.of(new LlmMessage("user", "Weather in Paris?")), null, null, null, null,
        SamplingParameters.NONE, Set.of(features));
    return new PassThroughRequest(request, BODY);
  }
}
