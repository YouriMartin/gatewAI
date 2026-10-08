package io.github.yourimartin.gatewai.infrastructure.llm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalPredictionSet;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.PromptHash;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.routing.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.routing.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.routing.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationOpening;
import io.github.yourimartin.gatewai.domain.model.routing.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.model.routing.RequestEmbeddingMemo;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

@Component
class RoutingAdvisor implements CallAdvisor, StreamAdvisor {

  private static final Logger LOG =
      LoggerFactory.getLogger(RoutingAdvisor.class);

  private final ComplexityClassifier classifier;
  private final ModelRegistry modelRegistry;
  private final DecisionRecorder decisionRecorder;
  private final DecisionMetricsRecorder decisionMetrics;
  private final RoutingConfigVersionTracker configVersion;
  private final CalibrationUseCase calibrations;
  private final ClassifierProperties properties;
  private final ConversationStickiness stickiness;

  RoutingAdvisor(ComplexityClassifier classifier,
                 ModelRegistry modelRegistry,
                 DecisionRecorder decisionRecorder,
                 DecisionMetricsRecorder decisionMetrics,
                 RoutingConfigVersionTracker configVersion,
                 CalibrationUseCase calibrations,
                 ClassifierProperties properties,
                 ConversationStickiness stickiness) {
    this.classifier = classifier;
    this.modelRegistry = modelRegistry;
    this.decisionRecorder = decisionRecorder;
    this.decisionMetrics = decisionMetrics;
    this.configVersion = configVersion;
    this.calibrations = calibrations;
    this.properties = properties;
    this.stickiness = stickiness;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request,
                                       CallAdvisorChain chain) {
    String userText = extractUserText(request);
    if (userText == null || userText.isBlank()) {
      return chain.nextCall(request);
    }

    if (honourPin(request, userText)) {
      return chain.nextCall(request);
    }

    List<ConversationOpening.Turn> turns =
        ConversationStickiness.turns(request.prompt());
    ModelDefinition target = route(userText, turns);
    if (target == null) {
      return chain.nextCall(request);
    }

    ChatClientResponse response = chain.nextCall(reroute(request, target));
    if (!ConversationOpening.hasHistory(turns)) {
      stickiness.recordFirstTurn(turns, answerText(response), target);
    }
    return stampRoutedModel(response, target.modelId());
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                               StreamAdvisorChain chain) {
    String userText = extractUserText(request);
    if (userText == null || userText.isBlank()) {
      return chain.nextStream(request);
    }

    if (honourPin(request, userText)) {
      return chain.nextStream(request);
    }

    List<ConversationOpening.Turn> turns =
        ConversationStickiness.turns(request.prompt());
    ModelDefinition target = route(userText, turns);
    if (target == null) {
      return chain.nextStream(request);
    }

    Flux<ChatClientResponse> stream = chain.nextStream(reroute(request, target));
    if (!ConversationOpening.hasHistory(turns)) {
      // A first turn is recorded on completion, with the answer as the client
      // received it: the concatenated deltas it will send back next turn.
      StringBuilder answer = new StringBuilder();
      stream = stream
          .doOnNext(response -> appendDelta(answer, response))
          .doOnComplete(() ->
              stickiness.recordFirstTurn(turns, answer.toString(), target));
    }
    return stream.map(response -> stampRoutedModel(response, target.modelId()));
  }

  private static ChatClientRequest reroute(ChatClientRequest request,
                                           ModelDefinition target) {
    return ChatClientRequest.builder()
        .prompt(reroutePrompt(request.prompt(), target.modelId()))
        .context(request.context())
        .build();
  }

  /**
   * Tells the advisors upstream which registry model answered (v4 A.3). The
   * provider reports its own name for the model — often a dated variant of the
   * registry id — so the cache stores this one, and a cached first turn can
   * then start a conversation on a model the registry knows.
   */
  private static ChatClientResponse stampRoutedModel(ChatClientResponse response,
                                                     String modelId) {
    if (response == null || response.chatResponse() == null) {
      return response;
    }
    return ChatClientResponse.builder()
        .chatResponse(ChatResponse.builder()
            .from(response.chatResponse())
            .metadata(LlmResponse.ROUTED_MODEL_METADATA_KEY, modelId)
            .build())
        .context(response.context())
        .build();
  }

  private static String answerText(ChatClientResponse response) {
    StringBuilder answer = new StringBuilder();
    appendDelta(answer, response);
    return answer.toString();
  }

  private static void appendDelta(StringBuilder answer, ChatClientResponse response) {
    ChatResponse chatResponse = response == null ? null : response.chatResponse();
    Generation result = chatResponse == null ? null : chatResponse.getResult();
    if (result != null && result.getOutput() != null
        && result.getOutput().getText() != null) {
      answer.append(result.getOutput().getText());
    }
  }

  /**
   * Honours a client that named a registered model id (v2 batch 4, D3).
   *
   * <p>Until this batch the router classified <b>every</b> non-blank prompt and
   * overwrote the requested model, which made {@code CLIENT_PINNED} a reason
   * that could never fire and the documented "clients may pin any registered
   * model id" untrue. It is true now: a registered id is honoured as sent, the
   * prompt is left alone, and the trace says why nothing was classified.
   *
   * <p>Only <b>registered</b> ids pin. An unknown id still goes through routing
   * exactly as before — the egress has no fallback provider, so honouring one
   * would turn a routed request into a 400.
   *
   * @return true when the request must be passed through untouched
   */
  private boolean honourPin(ChatClientRequest request, String userText) {
    if (!properties.isClientPinning()) {
      return false;
    }
    ChatOptions options = request.prompt().getOptions();
    String requested = options == null ? null : options.getModel();
    if (requested == null || requested.isBlank()) {
      return false;
    }

    long startNanos = System.nanoTime();
    ModelDefinition pinned = modelRegistry.findByModelId(requested).orElse(null);
    if (pinned == null) {
      return false;
    }

    LOG.info("Client pinned {} (tier={}), skipping classification",
        pinned.modelId(), pinned.tier());
    recordPinned(userText, pinned,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    return true;
  }

  /**
   * Classifies, places the request in its conversation and records the
   * decision.
   *
   * @return the model to rewrite to, or null when the request must pass through
   *         because no model is registered for the tier
   */
  private ModelDefinition route(String userText, List<ConversationOpening.Turn> turns) {
    long startNanos = System.nanoTime();

    ClassificationOutcome outcome = classifier.classify(userText);
    ConversationStickiness.Placement placement =
        stickiness.place(turns, userText, outcome);
    ModelDefinition target = placement.model();

    if (target == null) {
      LOG.info("No model configured for tier {}, using default", placement.tier());
    } else {
      LOG.info("Routing to {} (tier={}, model={}, conversation={})",
          target.provider(), placement.tier(), target.modelId(), placement.routing());
    }

    long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    recordDecision(userText, outcome, placement, latencyMs);
    return target;
  }

  /**
   * Never throws: the trace exists to explain requests, not to break them. The
   * recorder itself is non-blocking, but building the row (hashing, reading the
   * config version) happens here and must be equally harmless.
   */
  private void recordDecision(String userText, ClassificationOutcome outcome,
                              ConversationStickiness.Placement placement,
                              long latencyMs) {
    try {
      ModelDefinition target = placement.model();
      ClassificationJustification justification = outcome.justification();
      DecisionReason reason = target == null
          ? DecisionReason.NO_MODEL_FOR_TIER
          : DecisionReason.from(justification);

      CalibrationState calibration =
          calibrations.state(CalibrationTarget.ROUTING);

      publish(new RoutingDecision(
          UUID.randomUUID(),
          correlationId(),
          Instant.now(),
          PromptHash.of(userText),
          userText.length(),
          RequestEmbeddingMemo.current()
              .flatMap(memo -> memo.embeddingModelId()).orElse(null),
          configVersion.current(),
          configuredStrategy(justification),
          justification.strategy(),
          justification,
          reason,
          placement.tier(),
          target == null ? null : target.modelId(),
          latencyMs,
          conformalSet(justification, calibration),
          calibration.isApplied() ? calibration.calibration().alpha() : null,
          escalatedTo(justification),
          placement.routing(),
          placement.classifiedTier(),
          placement.fingerprint()));
    } catch (RuntimeException e) {
      LOG.warn("Could not build routing decision: {}", e.toString());
    }
  }

  /**
   * Records a pinned request (v2 batch 4). The justification is null here and
   * only here: no classifier ran, and a {@code Pinned} variant would have
   * carried nothing that {@code chosen_model_id} and {@code chosen_tier} do not
   * already say. {@code effective_strategy} is null for the same reason —
   * nothing decided a tier, the client did.
   */
  private void recordPinned(String userText, ModelDefinition pinned,
                            long latencyMs) {
    try {
      publish(new RoutingDecision(
          UUID.randomUUID(),
          correlationId(),
          Instant.now(),
          PromptHash.of(userText),
          userText.length(),
          RequestEmbeddingMemo.current()
              .flatMap(memo -> memo.embeddingModelId()).orElse(null),
          configVersion.current(),
          properties.getStrategy(),
          null,
          null,
          DecisionReason.CLIENT_PINNED,
          pinned.tier(),
          pinned.modelId(),
          latencyMs,
          null,
          null,
          null,
          null,
          null,
          null));
    } catch (RuntimeException e) {
      LOG.warn("Could not build pinned routing decision: {}", e.toString());
    }
  }

  /**
   * Sends the decision to the trace and to the metrics (v2 batch 6).
   *
   * <p>Both, from the same object, so an aggregate can never disagree with the
   * row behind it. The metrics call is <b>not</b> made from the recorder:
   * persistence is switchable ({@code gatewai.decisions.enabled=false}) and
   * turning the trace off must not also blind the dashboards.
   */
  private void publish(RoutingDecision decision) {
    decisionRecorder.record(decision);
    decisionMetrics.record(decision);
  }

  /**
   * The tiers whose route cleared the calibrated threshold, best first
   * (v2 batch 3).
   *
   * <p>Derived from the scores the classifier already reported rather than
   * recomputed, so the recorded set is by construction the one the decision was
   * taken from — and, since v2 batch 4, the very set the cascade escalated on.
   * Null when no calibration applied: a decision taken without one has no
   * prediction set, which is not the same as having an empty one.
   */
  private static List<ModelTier> conformalSet(
      ClassificationJustification justification, CalibrationState calibration) {

    if (!calibration.isApplied()) {
      return null;
    }
    return ConformalPredictionSet
        .of(candidates(justification), calibration).tiers();
  }

  /** Per-route scores, whether the strategy decided or handed over below them. */
  private static List<ClassificationJustification.RouteCandidate> candidates(
      ClassificationJustification justification) {
    return switch (justification) {
      case null -> List.of();
      case ClassificationJustification.Embedding embedding -> embedding.candidates();
      case ClassificationJustification.Fallback fallback ->
          fallback.evidence() instanceof ClassificationJustification.Embedding evidence
              ? evidence.candidates() : List.of();
      case ClassificationJustification.Cascade cascade -> {
        // Level 3 keeps the scores it escalated on as evidence; levels 1 and 2
        // keep them (or have none) in whatever decided.
        List<ClassificationJustification.RouteCandidate> evidence =
            candidates(cascade.escalatedOn());
        yield evidence.isEmpty() ? candidates(cascade.decided()) : evidence;
      }
      default -> List.of();
    };
  }

  /** How far the cascade went, or null when the cascade was not in play. */
  private static CascadeLevel escalatedTo(
      ClassificationJustification justification) {
    return justification instanceof ClassificationJustification.Cascade cascade
        ? cascade.level() : null;
  }

  /**
   * The strategy that was configured, which the justification already knows:
   * on a hand-over it is the one that stepped aside, on a cascade it is the
   * cascade itself, otherwise the one that decided.
   */
  private static ClassificationStrategy configuredStrategy(
      ClassificationJustification justification) {
    return switch (justification) {
      case ClassificationJustification.Cascade ignored ->
          ClassificationStrategy.CASCADE;
      case ClassificationJustification.Fallback fallback ->
          fallback.fallbackFrom();
      case ClassificationJustification.FailSafe failSafe ->
          failSafe.fallbackFrom();
      default -> justification.strategy();
    };
  }

  private static String correlationId() {
    return RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get().traceId() : null;
  }

  @Override
  public String getName() {
    return "Routing";
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 1;
  }

  private static String extractUserText(ChatClientRequest request) {
    UserMessage userMessage = request.prompt().getUserMessage();
    return userMessage != null ? userMessage.getText() : null;
  }

  /**
   * The prompt re-targeted at {@code targetModelId}, every other option kept.
   * Until v4 B.1 this rebuilt the options from four of them, which dropped the
   * penalties and the seed of every routed request; mutating keeps whatever the
   * request carried — including the seed of {@link GatewaiChatOptions}.
   */
  private static Prompt reroutePrompt(Prompt original,
                                      String targetModelId) {
    ChatOptions originalOptions = original.getOptions();
    ChatOptions.Builder<?> builder = originalOptions == null
        ? ChatOptions.builder() : originalOptions.mutate();
    builder.model(targetModelId);
    return new Prompt(original.getInstructions(), builder.build());
  }
}
