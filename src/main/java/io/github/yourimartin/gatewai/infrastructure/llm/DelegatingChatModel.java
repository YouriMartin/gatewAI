package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.FailoverEvent;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverCause;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverSequence;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EgressHealth;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * Provider-agnostic egress (Phase 7.2, generalized in Phase 8). The
 * {@code RoutingAdvisor} rewrites the prompt's model id per tier; this
 * delegating {@link ChatModel} resolves that id through the {@link ModelRegistry}
 * and dispatches to the matching {@link ProviderChatModels provider instance} —
 * any registered model on any configured provider, behind one advisor chain.
 *
 * <p>Marked {@link Primary} so Spring AI builds its {@code ChatClient} on this
 * model rather than on a single provider. There is <b>no fallback provider</b>:
 * a model id absent from the registry raises {@link UnknownModelException}
 * (mapped to an OpenAI-style 400), never a silent call to another vendor. A
 * client may also pin any registered model id directly; routing only rewrites it.
 *
 * <p>The advisor chain sets portable options on the prompt, but every Spring AI
 * 2.0 provider model expects its <b>own</b> options type: {@code OpenAiChatModel}
 * and {@code OllamaChatModel} hard-cast to it, and {@code AnthropicChatModel}
 * silently replaces anything else with empty options (no model, no max tokens).
 * So the prompt is always re-based on the target instance's own options (which
 * hold its model, credentials and defaults), with the request's options merged
 * over them — and the {@link GatewaiChatOptions#getSeed() seed} set where the
 * provider takes one (v4 B.1). Until B.1 only Ollama was re-based, which left
 * every routed call to an {@code openai}/{@code openai-compatible} instance
 * failing with a {@code ClassCastException}.
 *
 * <p>Since v4 B.5 it is also where a routed request <b>fails over</b> (ADR 0017):
 * the only place that sees a provider's exception before Spring AI wraps it. A
 * request the router flagged ({@link GatewaiChatOptions#isFailover()}) that fails
 * for a transient reason — connection, timeout, 429, 5xx — before its first chunk
 * is sent to the next entry of its tier, in failover order, with cooling entries
 * skipped. Every other failure, and any failure after the first chunk, reaches
 * the client. Provider exceptions are reported as {@link UpstreamFailureException},
 * like the pass-through path's, and the answer names the model that gave it
 * ({@link #ANSWERED_MODEL_METADATA_KEY}) when that is not the one first chosen.
 */
@Component
@Primary
@Profile("!mock")
class DelegatingChatModel implements ChatModel {

  /**
   * Response metadata naming the registry model that answered, set only when a
   * failover made it another one than the prompt named (v4 B.5).
   */
  static final String ANSWERED_MODEL_METADATA_KEY = "gatewai.egress.answered-model";

  private static final Logger LOG = LoggerFactory.getLogger(DelegatingChatModel.class);

  private final ProviderChatModels providers;
  private final ModelRegistry modelRegistry;
  private final EgressHealth health;
  private final DecisionRecorder decisionRecorder;
  private final DecisionMetricsRecorder decisionMetrics;

  DelegatingChatModel(ProviderChatModels providers, ModelRegistry modelRegistry,
                      EgressHealth health, DecisionRecorder decisionRecorder,
                      DecisionMetricsRecorder decisionMetrics) {
    this.providers = providers;
    this.modelRegistry = modelRegistry;
    this.health = health;
    this.decisionRecorder = decisionRecorder;
    this.decisionMetrics = decisionMetrics;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    ModelDefinition first = resolve(prompt);
    boolean failover = GatewaiChatOptions.failoverOf(prompt.getOptions());
    FailoverSequence sequence = sequence(first, failover);
    String correlationId = correlationId();
    ModelDefinition model = sequence.next().orElse(first);
    while (true) {
      try {
        ChatResponse response = instance(model).chatModel().call(adapt(prompt, model));
        health.succeeded(model);
        return stampAnswered(response, first, model);
      } catch (RuntimeException e) {
        // Anything that is not an upstream failure (a bug, a refused request)
        // reaches the client as it is, and never fails over.
        UpstreamFailureException failure =
            UpstreamFailures.classify(e, model.modelId()).orElseThrow(() -> e);
        model = afterFailure(failure, model, sequence, failover, correlationId);
      }
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    ModelDefinition first = resolve(prompt);
    boolean failover = GatewaiChatOptions.failoverOf(prompt.getOptions());
    return Flux.defer(() -> {
      FailoverSequence sequence = sequence(first, failover);
      return attempt(prompt, first, sequence.next().orElse(first), sequence, failover,
          correlationId());
    });
  }

  /**
   * One streamed attempt, and the next one if it fails before its first chunk.
   * After a chunk has gone downstream the client has started reading an answer,
   * so a failure ends the stream as it is.
   */
  private Flux<ChatResponse> attempt(Prompt prompt, ModelDefinition first, ModelDefinition model,
                                     FailoverSequence sequence, boolean failover,
                                     String correlationId) {
    AtomicBoolean emitted = new AtomicBoolean();
    return Flux.defer(() -> instance(model).chatModel().stream(adapt(prompt, model)))
        .doOnNext(response -> {
          if (emitted.compareAndSet(false, true)) {
            health.succeeded(model);
          }
        })
        .doOnComplete(() -> {
          if (!emitted.get()) {
            health.succeeded(model);
          }
        })
        .map(response -> stampAnswered(response, first, model))
        .onErrorResume(e -> {
          if (emitted.get()) {
            countMidStreamFailure(e, model);
            return Flux.error(e);
          }
          Optional<UpstreamFailureException> failure =
              UpstreamFailures.classify(e, model.modelId());
          if (failure.isEmpty()) {
            return Flux.error(e);
          }
          ModelDefinition next;
          try {
            next = afterFailure(failure.get(), model, sequence, failover, correlationId);
          } catch (UpstreamFailureException last) {
            return Flux.error(last);
          }
          return attempt(prompt, first, next, sequence, failover, correlationId);
        });
  }

  /**
   * Decides what a failed attempt leads to: the next model to try, or the
   * failure the client receives. Only a transient failure counts against the
   * model's health, and only a request the router flagged moves on.
   */
  private ModelDefinition afterFailure(UpstreamFailureException failure,
                                       ModelDefinition failed, FailoverSequence sequence,
                                       boolean failover, String correlationId) {
    Optional<FailoverCause> cause = FailoverCause.of(failure);
    if (cause.isEmpty()) {
      throw failure;
    }
    health.failed(failed);
    if (!failover) {
      throw failure;
    }
    ModelDefinition next = sequence.next().orElse(null);
    record(FailoverEvent.of(correlationId, FailoverEvent.Path.CHAIN, failed, next,
        cause.get(), failure));
    if (next == null) {
      LOG.warn("Model '{}' failed ({}) and its tier {} has nothing left to try",
          failed.modelId(), cause.get(), failed.tier());
      throw failure;
    }
    LOG.warn("Model '{}' failed ({}): failing over to '{}' in tier {}",
        failed.modelId(), cause.get(), next.modelId(), failed.tier());
    return next;
  }

  /** A failure after the first chunk does not fail over, but it is still news. */
  private void countMidStreamFailure(Throwable thrown, ModelDefinition model) {
    UpstreamFailures.classify(thrown, model.modelId())
        .flatMap(FailoverCause::of)
        .ifPresent(cause -> health.failed(model));
  }

  private FailoverSequence sequence(ModelDefinition first, boolean failover) {
    return failover
        ? FailoverSequence.of(first, modelRegistry.findByTier(first.tier()), health::admit)
        : FailoverSequence.single(first);
  }

  /** Never throws: the trace explains a request, it must not fail one. */
  private void record(FailoverEvent event) {
    try {
      decisionRecorder.record(event);
      decisionMetrics.record(event);
    } catch (RuntimeException e) {
      LOG.warn("Could not record a failover: {}", e.toString());
    }
  }

  /** Names the answering model when a failover made it another one. */
  private static ChatResponse stampAnswered(ChatResponse response, ModelDefinition first,
                                            ModelDefinition answered) {
    if (answered.equals(first) || response == null) {
      return response;
    }
    return ChatResponse.builder()
        .from(response)
        .metadata(ANSWERED_MODEL_METADATA_KEY, answered.modelId())
        .build();
  }

  private static String correlationId() {
    return RequestContext.CURRENT.isBound() ? RequestContext.CURRENT.get().traceId() : null;
  }

  /**
   * Neutral defaults — never leak one provider's options onto another's call. Of
   * the gateway's type, so the {@code ChatClient}'s merge keeps the request's seed.
   */
  @Override
  public ChatOptions getOptions() {
    return GatewaiChatOptions.builder().build();
  }

  /** Resolves the prompt's model id to a registry entry served by this gateway, or fails. */
  private ModelDefinition resolve(Prompt prompt) {
    ChatOptions options = prompt.getOptions();
    String modelId = options != null ? options.getModel() : null;
    if (modelId == null || modelId.isBlank()) {
      throw new UnknownModelException(
          "No model id on the request. Use one of the model ids declared in the gateway's registry.");
    }
    ModelDefinition definition = modelRegistry.findByModelId(modelId)
        .orElseThrow(() -> new UnknownModelException("Unknown model '" + modelId
            + "'. Use one of the model ids declared in the gateway's registry."));
    instance(definition);
    return definition;
  }

  /** The provider instance serving {@code model}; startup validated that it exists. */
  private ProviderChatModels.ProviderInstance instance(ModelDefinition model) {
    return providers.find(model.provider())
        .orElseThrow(() -> new UnknownModelException("Model '" + model.modelId()
            + "' maps to provider '" + model.provider()
            + "', which is not configured on this gateway."));
  }

  /**
   * The prompt for {@code model}: its instance's own options type, the request's
   * options merged in, and the model id set to {@code model}'s — which differs
   * from the prompt's on a failover.
   */
  private Prompt adapt(Prompt prompt, ModelDefinition model) {
    ProviderChatModels.ProviderInstance target = instance(model);
    ChatOptions defaults = target.chatModel().getOptions();
    ChatOptions.Builder<?> options = defaults == null
        ? ChatOptions.builder() : defaults.mutate();
    ChatOptions requested = prompt.getOptions();
    if (requested != null) {
      options.combineWith(requested.mutate());
    }
    options.model(model.modelId());
    Integer seed = intSeed(GatewaiChatOptions.seedOf(requested));
    if (options instanceof OpenAiChatOptions.Builder openAi) {
      openAi.seed(seed);
      if (target.type() == ProviderProperties.ProviderType.OPENAI
          && requested != null && requested.getMaxTokens() != null) {
        // OpenAI deprecated max_tokens and its reasoning models reject it; servers
        // that merely speak the format (openai-compatible) still expect max_tokens.
        openAi.maxTokens(null).maxCompletionTokens(requested.getMaxTokens());
      }
    } else if (options instanceof OllamaChatOptions.Builder ollama) {
      ollama.seed(seed);
    }
    // Anthropic has no seed: it is ignored there, as documented.
    return new Prompt(prompt.getInstructions(), options.build());
  }

  /** Both providers that take a seed take a 32-bit one; a larger seed is not sent. */
  private static Integer intSeed(Long seed) {
    return seed == null || seed != seed.intValue() ? null : seed.intValue();
  }
}
