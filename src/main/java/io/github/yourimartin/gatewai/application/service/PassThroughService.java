package io.github.yourimartin.gatewai.application.service;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.PromptHash;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.ClientDisconnectedException;
import io.github.yourimartin.gatewai.domain.model.llm.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughRequest;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughStreamOutcome;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.model.llm.UnsupportedFeatureException;
import io.github.yourimartin.gatewai.domain.model.routing.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.in.PassThroughStream;
import io.github.yourimartin.gatewai.domain.port.in.PassThroughUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.PassThroughClient;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;
import io.github.yourimartin.gatewai.domain.port.out.UpstreamEventStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The pass-through path (ADR 0016, v4 B.3): a request the advisor chain cannot
 * honour goes, as the client wrote it, to one registered model — the one it pins,
 * or {@code gatewai.passthrough.default-model}. It is never classified and never
 * cached, and the trace says so: a {@code BYPASS} cache decision and a
 * {@code PASSTHROUGH_*} routing decision, recorded before the call. The served
 * request is then accounted by the same code as the chain.
 */
@Service
final class PassThroughService implements PassThroughUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(PassThroughService.class);

  private final PassThroughClient client;
  private final ModelRegistry modelRegistry;
  private final DecisionRecorder decisionRecorder;
  private final DecisionMetricsRecorder decisionMetrics;
  private final RequestAccounting accounting;
  private final MetricsRecorder metricsRecorder;
  private final ModelDefinition defaultTarget;

  PassThroughService(PassThroughClient client,
                     ModelRegistry modelRegistry,
                     DecisionRecorder decisionRecorder,
                     DecisionMetricsRecorder decisionMetrics,
                     RequestLogRepository requestLogRepository,
                     CarbonIntensityProvider carbonIntensityProvider,
                     GreenAccountant greenAccountant,
                     MetricsRecorder metricsRecorder,
                     ProviderRegions providerRegions,
                     CloudRegionZones cloudRegionZones,
                     @Value("${gatewai.passthrough.default-model:}") String defaultModel) {
    this.client = client;
    this.modelRegistry = modelRegistry;
    this.decisionRecorder = decisionRecorder;
    this.decisionMetrics = decisionMetrics;
    this.accounting = new RequestAccounting(requestLogRepository, modelRegistry,
        carbonIntensityProvider, greenAccountant, metricsRecorder, providerRegions,
        cloudRegionZones);
    this.metricsRecorder = metricsRecorder;
    this.defaultTarget = resolveDefault(modelRegistry, defaultModel);
  }

  /**
   * The configured default, or the first entry of the highest tier. A configured
   * id the registry does not hold fails startup: a typo here would otherwise
   * surface as a 400 on the first tool call, in production.
   */
  private static ModelDefinition resolveDefault(ModelRegistry registry, String modelId) {
    if (modelId != null && !modelId.isBlank()) {
      return registry.findByModelId(modelId.strip()).orElseThrow(() ->
          new IllegalStateException("gatewai.passthrough.default-model='" + modelId.strip()
              + "' is not a model id of gatewai.models.registry. Registered ids: "
              + registry.allModels().stream().map(ModelDefinition::modelId).toList()));
    }
    List<ModelDefinition> premium = registry.findByTier(ModelTier.CLOUD_PREMIUM);
    return premium.isEmpty() ? null : premium.getFirst();
  }

  @Override
  public PassThroughResponse forward(PassThroughRequest passThrough) {
    Prepared prepared = prepare(passThrough.request());
    PassThroughResponse response = client.forward(prepared.target(), passThrough.body());
    account(prepared, response.usage());
    return response;
  }

  @Override
  public PassThroughStream openStream(PassThroughRequest passThrough) {
    Prepared prepared = prepare(passThrough.request());
    UpstreamEventStream upstream = client.openStream(prepared.target(), passThrough.body());
    return new RelayedStream(prepared, upstream);
  }

  /**
   * What both paths share, on the request thread: the target, the refusal of what
   * it would ignore, and the trace — recorded before the upstream is called, so a
   * failed call still explains where it was sent.
   */
  private Prepared prepare(LlmRequest request) {
    long startNanos = System.nanoTime();
    String correlationId = RequestAccounting.correlationId();
    Optional<ModelDefinition> pinned = request.model() == null
        ? Optional.empty() : modelRegistry.findByModelId(request.model());
    ModelDefinition target = pinned.orElse(defaultTarget);
    if (target == null) {
      throw new UnknownModelException("No model to pass this request through to: it names no"
          + " registered model, and the registry has no cloud-premium entry. Set"
          + " gatewai.passthrough.default-model.");
    }
    refuseIgnoredFeatures(request, target);

    String userText = lastUserText(request);
    long decisionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    recordCacheBypass(request, userText, correlationId);
    recordRouting(userText, target,
        pinned.isPresent() ? DecisionReason.PASSTHROUGH_PINNED : DecisionReason.PASSTHROUGH_DEFAULT,
        decisionMs, correlationId);
    return new Prepared(request, target, startNanos, correlationId,
        RequestAccounting.clientId());
  }

  /**
   * A request on its way, with the context captured on the request thread: a
   * stream is relayed from another one, where the Scoped Value is unbound.
   */
  private record Prepared(LlmRequest request, ModelDefinition target, long startNanos,
                          String correlationId, String clientId) {
  }

  /** Accounts a served request through the chain's code (ADR 0016). */
  private void account(Prepared prepared, TokenUsage reported) {
    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prepared.startNanos());
    TokenUsage usage = reported;
    if (usage == null) {
      LOG.warn("Upstream of model '{}' reported no usage: request {} is logged with zero"
          + " tokens, which means not accounted, not measured zero",
          prepared.target().modelId(), prepared.correlationId());
      usage = TokenUsage.NONE;
    }
    accounting.record(prepared.request(), prepared.target().modelId(), usage, false, latencyMs,
        prepared.correlationId(), prepared.clientId());
  }

  /**
   * A streamed pass-through (v4 B.4): relayed as it arrives, accounted once the
   * upstream ends, and counted however it ends. A client that goes away cancels
   * the upstream call; its tokens are billed by the provider but cannot be
   * accounted, since their count only comes in the last chunk — so the stream is
   * recorded as a disconnect, never as a zero-token request.
   */
  private final class RelayedStream implements PassThroughStream {

    private final Prepared prepared;
    private final UpstreamEventStream upstream;

    RelayedStream(Prepared prepared, UpstreamEventStream upstream) {
      this.prepared = prepared;
      this.upstream = upstream;
    }

    @Override
    public String model() {
      return prepared.target().modelId();
    }

    @Override
    public void relay(Consumer<String> onEvent) {
      String model = model();
      // Anything that is neither a clean end nor a disconnect is the upstream's.
      PassThroughStreamOutcome outcome = PassThroughStreamOutcome.UPSTREAM_FAILED;
      try {
        TokenUsage usage = upstream.relay(onEvent);
        outcome = PassThroughStreamOutcome.COMPLETED;
        account(prepared, usage);
      } catch (ClientDisconnectedException e) {
        outcome = PassThroughStreamOutcome.CLIENT_DISCONNECTED;
        LOG.warn("Client of request {} went away mid-stream: the call to model '{}' was"
            + " cancelled, and its tokens are not accounted", prepared.correlationId(), model);
        throw e;
      } finally {
        metricsRecorder.recordPassThroughStream(model, outcome);
      }
    }

    @Override
    public void cancel() {
      upstream.cancel();
    }
  }

  /** Refuses, before anything is sent or traced, what the target would silently drop. */
  private void refuseIgnoredFeatures(LlmRequest request, ModelDefinition target) {
    Set<PassThroughFeature> ignored = EnumSet.noneOf(PassThroughFeature.class);
    ignored.addAll(request.passThrough());
    ignored.retainAll(client.ignoredFeatures(target));
    if (!ignored.isEmpty()) {
      throw UnsupportedFeatureException.notHonouredBy(ignored, target.modelId(),
          client.describeEndpoint(target));
    }
  }

  /**
   * The cache was never consulted, and the row says why — the first marked
   * feature, in declaration order (ADR 0016). Never throws.
   */
  private void recordCacheBypass(LlmRequest request, String userText, String correlationId) {
    try {
      PassThroughFeature first = request.passThrough().stream().sorted().findFirst()
          .orElseThrow();
      publish(new CacheDecision(
          UUID.randomUUID(),
          correlationId,
          Instant.now(),
          PromptHash.of(userText),
          CacheOutcome.BYPASS,
          0, null, 0,
          null, null, null,
          null,
          null,
          CacheDecisionReason.passThrough(first),
          null));
    } catch (RuntimeException e) {
      LOG.warn("Could not record the pass-through cache decision: {}", e.toString());
    }
  }

  /**
   * Nothing was classified: no justification, no strategy, no calibration — the
   * same shape as a {@code CLIENT_PINNED} row, with a reason that says which path
   * took the request. Never throws.
   */
  private void recordRouting(String userText, ModelDefinition target, DecisionReason reason,
                             long latencyMs, String correlationId) {
    try {
      RoutingDecision decision = new RoutingDecision(
          UUID.randomUUID(),
          correlationId,
          Instant.now(),
          PromptHash.of(userText),
          userText.length(),
          null,
          null,
          null,
          null,
          null,
          reason,
          target.tier(),
          target.modelId(),
          latencyMs,
          null,
          null,
          null,
          null,
          null,
          null);
      decisionRecorder.record(decision);
      decisionMetrics.record(decision);
    } catch (RuntimeException e) {
      LOG.warn("Could not record the pass-through routing decision: {}", e.toString());
    }
  }

  private void publish(CacheDecision decision) {
    decisionRecorder.record(decision);
    decisionMetrics.record(decision);
  }

  /**
   * The text of the last user message, as the chain hashes it. Empty when there is
   * none — a request whose last turns are tool results still has a trace.
   */
  private static String lastUserText(LlmRequest request) {
    List<LlmMessage> messages = request.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      if ("user".equals(messages.get(i).role())) {
        return messages.get(i).content();
      }
    }
    return "";
  }
}
