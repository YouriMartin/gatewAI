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
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.FailoverEvent;
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
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.routing.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverCause;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverSequence;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.in.PassThroughStream;
import io.github.yourimartin.gatewai.domain.port.in.PassThroughUseCase;
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
 *
 * <p>Since v4 B.5 (ADR 0017) a request sent to the default model fails over, on a
 * transient failure before the first byte, to the next entry of that model's tier
 * that can honour it; a pinned one only when {@code gatewai.failover.pinned} says
 * so. The routing decision names the first target, the failover rows each move,
 * and the request log the model that answered.
 *
 * <p>Since v4 D.1 it also serves every request of a shadow client: to the model
 * it names — a registry id, or {@code auto} for the default — and never to a
 * guessed one. A shadow request the chain could have honoured records no
 * decision here: once its answer is relayed, {@link ShadowEvaluator} has the
 * chain decide on it, and those are its decisions, stamped {@code SHADOW}.
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
  private final EgressHealth health;
  private final ModelDefinition defaultTarget;
  private final boolean failoverPinned;
  private final ShadowEvaluator shadow;
  private final RelayedAnswerReader answers;

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
                     EgressHealth health,
                     ShadowEvaluator shadow,
                     RelayedAnswerReader answers,
                     @Value("${gatewai.passthrough.default-model:}") String defaultModel,
                     @Value("${gatewai.failover.pinned:false}") boolean failoverPinned) {
    this.client = client;
    this.modelRegistry = modelRegistry;
    this.decisionRecorder = decisionRecorder;
    this.decisionMetrics = decisionMetrics;
    this.accounting = new RequestAccounting(requestLogRepository, modelRegistry,
        carbonIntensityProvider, greenAccountant, metricsRecorder, providerRegions,
        cloudRegionZones);
    this.metricsRecorder = metricsRecorder;
    this.health = health;
    this.defaultTarget = resolveDefault(modelRegistry, defaultModel);
    this.failoverPinned = failoverPinned;
    this.shadow = shadow;
    this.answers = answers;
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
    Prepared prepared = prepare(passThrough);
    FailoverSequence sequence = sequence(prepared);
    ModelDefinition model = sequence.next().orElse(prepared.target());
    while (true) {
      try {
        PassThroughResponse response = client.forward(model, passThrough.body());
        health.succeeded(model);
        account(prepared, model, response.usage());
        if (prepared.evaluated()) {
          shadow.submit(prepared.context(), prepared.request(), answers.read(response));
        }
        return response;
      } catch (UpstreamFailureException e) {
        model = afterFailure(e, model, sequence, prepared);
      }
    }
  }

  /**
   * Opens the stream, failing over while no upstream has answered 2xx: once one
   * has, the relay has begun, and nothing fails over any more (ADR 0017).
   */
  @Override
  public PassThroughStream openStream(PassThroughRequest passThrough) {
    Prepared prepared = prepare(passThrough);
    FailoverSequence sequence = sequence(prepared);
    ModelDefinition model = sequence.next().orElse(prepared.target());
    while (true) {
      try {
        UpstreamEventStream upstream = client.openStream(model, passThrough.body());
        health.succeeded(model);
        return new RelayedStream(prepared, model, upstream,
            prepared.evaluated() ? answers.streamed(model.modelId()) : null);
      } catch (UpstreamFailureException e) {
        model = afterFailure(e, model, sequence, prepared);
      }
    }
  }

  /**
   * The request's candidates: its target, then — when it may fail over — the
   * rest of the target's tier in failover order, without the entries whose
   * endpoint would ignore one of its marked features (ADR 0016). Failover never
   * turns a refusal into a different model's answer.
   */
  private FailoverSequence sequence(Prepared prepared) {
    if (!prepared.failover()) {
      return FailoverSequence.single(prepared.target());
    }
    List<ModelDefinition> honouring = modelRegistry.findByTier(prepared.target().tier()).stream()
        .filter(model -> model.equals(prepared.target())
            || ignored(prepared.request(), model).isEmpty())
        .toList();
    return FailoverSequence.of(prepared.target(), honouring, health::admit);
  }

  /**
   * What a failed attempt leads to: the next model, or the failure the client
   * receives. Only a transient failure counts against the model's health, and
   * only a request allowed to fail over moves on.
   */
  private ModelDefinition afterFailure(UpstreamFailureException failure, ModelDefinition failed,
                                       FailoverSequence sequence, Prepared prepared) {
    Optional<FailoverCause> cause = FailoverCause.of(failure);
    if (cause.isEmpty()) {
      throw failure;
    }
    health.failed(failed);
    if (!prepared.failover()) {
      throw failure;
    }
    ModelDefinition next = sequence.next().orElse(null);
    recordFailover(FailoverEvent.of(prepared.correlationId(), FailoverEvent.Path.PASS_THROUGH,
        failed, next, cause.get(), failure));
    if (next == null) {
      LOG.warn("Pass-through to model '{}' failed ({}) and its tier {} has nothing left to try",
          failed.modelId(), cause.get(), failed.tier());
      throw failure;
    }
    LOG.warn("Pass-through to model '{}' failed ({}): failing over to '{}' in tier {}",
        failed.modelId(), cause.get(), next.modelId(), failed.tier());
    return next;
  }

  /**
   * What both paths share, on the request thread: the target, the refusal of what
   * it would ignore, and the trace — recorded before the upstream is called, so a
   * failed call still explains where it was sent.
   */
  private Prepared prepare(PassThroughRequest passThrough) {
    long startNanos = System.nanoTime();
    LlmRequest request = passThrough.request();
    String correlationId = RequestAccounting.correlationId();
    Optional<ModelDefinition> pinned = request.model() == null
        ? Optional.empty() : modelRegistry.findByModelId(request.model());
    if (passThrough.shadow() && pinned.isEmpty() && !isAuto(request.model())) {
      throw new UnknownModelException("Unknown model '" + request.model() + "'. A shadow"
          + " client is served by the model it names: use one of the model ids declared in"
          + " the gateway's registry, or 'auto' for the default model.");
    }
    ModelDefinition target = pinned.orElse(defaultTarget);
    if (target == null) {
      throw new UnknownModelException("No model to pass this request through to: it names no"
          + " registered model, and the registry has no cloud-premium entry. Set"
          + " gatewai.passthrough.default-model.");
    }
    refuseIgnoredFeatures(request, target);

    boolean evaluated = passThrough.evaluatedInShadow();
    if (!evaluated) {
      String userText = lastUserText(request);
      long decisionMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      recordCacheBypass(request, userText, correlationId);
      recordRouting(userText, target,
          pinned.isPresent() ? DecisionReason.PASSTHROUGH_PINNED
              : DecisionReason.PASSTHROUGH_DEFAULT,
          decisionMs, correlationId);
      if (passThrough.shadow()) {
        // The chain would have passed it through too: these rows are its verdict.
        shadow.bypassed();
      }
    }
    RequestContext context = RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get() : null;
    return new Prepared(request, target, pinned.isEmpty() || failoverPinned, startNanos,
        correlationId, RequestAccounting.clientId(), evaluated && context != null, context);
  }

  /** {@code auto}, or no model at all: the shadow client leaves the choice to the gateway. */
  private static boolean isAuto(String model) {
    return model == null || model.isBlank() || "auto".equals(model.strip());
  }

  /**
   * A request on its way, with the context captured on the request thread: a
   * stream is relayed from another one, where the Scoped Value is unbound.
   *
   * @param target    where it is sent first
   * @param failover  whether it may fail over inside {@code target}'s tier
   * @param evaluated whether the chain decides on its answer in shadow (v4 D.1)
   * @param context   the request's context, null when none is bound
   */
  private record Prepared(LlmRequest request, ModelDefinition target, boolean failover,
                          long startNanos, String correlationId, String clientId,
                          boolean evaluated, RequestContext context) {
  }

  /** Accounts a request {@code model} served, through the chain's code (ADR 0016). */
  private void account(Prepared prepared, ModelDefinition model, TokenUsage reported) {
    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - prepared.startNanos());
    TokenUsage usage = reported;
    if (usage == null) {
      LOG.warn("Upstream of model '{}' reported no usage: request {} is logged with zero"
          + " tokens, which means not accounted, not measured zero",
          model.modelId(), prepared.correlationId());
      usage = TokenUsage.NONE;
    }
    accounting.record(prepared.request(), model.modelId(), usage, false, latencyMs,
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
    private final ModelDefinition answering;
    private final UpstreamEventStream upstream;
    /** Reads the answer back for the shadow evaluation; null when there is none. */
    private final RelayedAnswerReader.StreamedAnswer answer;

    RelayedStream(Prepared prepared, ModelDefinition answering, UpstreamEventStream upstream,
                  RelayedAnswerReader.StreamedAnswer answer) {
      this.prepared = prepared;
      this.answering = answering;
      this.upstream = upstream;
      this.answer = answer;
    }

    @Override
    public String model() {
      return answering.modelId();
    }

    @Override
    public void relay(Consumer<String> onEvent) {
      String model = model();
      // Anything that is neither a clean end nor a disconnect is the upstream's.
      PassThroughStreamOutcome outcome = PassThroughStreamOutcome.UPSTREAM_FAILED;
      try {
        // The client has each event before the shadow reader looks at it.
        TokenUsage usage = upstream.relay(answer == null ? onEvent : event -> {
          onEvent.accept(event);
          answer.accept(event);
        });
        outcome = PassThroughStreamOutcome.COMPLETED;
        account(prepared, answering, usage);
        if (answer != null) {
          shadow.submit(prepared.context(), prepared.request(), answer.answer(usage));
        }
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
    Set<PassThroughFeature> ignored = ignored(request, target);
    if (!ignored.isEmpty()) {
      throw UnsupportedFeatureException.notHonouredBy(ignored, target.modelId(),
          client.describeEndpoint(target));
    }
  }

  /** The request's marked features that {@code target}'s endpoint would ignore. */
  private Set<PassThroughFeature> ignored(LlmRequest request, ModelDefinition target) {
    Set<PassThroughFeature> ignored = EnumSet.noneOf(PassThroughFeature.class);
    ignored.addAll(request.passThrough());
    ignored.retainAll(client.ignoredFeatures(target));
    return ignored;
  }

  /** Never throws: the trace explains a request, it must not fail one. */
  private void recordFailover(FailoverEvent event) {
    try {
      decisionRecorder.record(event);
      decisionMetrics.record(event);
    } catch (RuntimeException e) {
      LOG.warn("Could not record a pass-through failover: {}", e.toString());
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
          null,
          RequestContext.currentMode()));
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
          null,
          RequestContext.currentMode());
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
