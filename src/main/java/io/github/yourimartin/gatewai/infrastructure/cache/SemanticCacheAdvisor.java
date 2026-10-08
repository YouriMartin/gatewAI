package io.github.yourimartin.gatewai.infrastructure.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.llm.FinishReason;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationOpening;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

/**
 * The semantic cache, first in the advisor chain: short-circuits on a hit by
 * never calling the rest of the chain.
 *
 * <p>Since v4 A.2 (ADR 0014) similarity is compared on the last user turn
 * <b>only inside an identical conversation context</b>: every lookup filters on
 * the {@link CacheScope} of the request — system prompt, history, pinned model,
 * end user, stop sequences — so the embedding never has to carry the context it
 * cannot see. A last turn longer than the embedding window is matched exactly.
 */
@Component
class SemanticCacheAdvisor implements CallAdvisor, StreamAdvisor {

  static final String CACHE_RESPONSE_KEY = "cached_response";
  static final String CACHE_MODEL_KEY = "cached_model";
  static final String CACHE_FINISH_REASON_KEY = "cached_finish_reason";
  static final String CACHE_PROMPT_TOKENS_KEY = "cached_prompt_tokens";
  static final String CACHE_COMPLETION_TOKENS_KEY = "cached_completion_tokens";
  static final String CREATED_AT_KEY = "created_at";
  static final String CLIENT_ID_KEY = "client_id";
  /** Correlation id of the request that produced the cached answer. */
  static final String CORRELATION_ID_KEY = "correlation_id";
  /** The conversation scope an entry is valid in (ADR 0014). */
  static final String CACHE_SCOPE_KEY = "cache_scope";
  /** SHA-256 of the full last user turn, for exact matching past the window. */
  static final String TURN_HASH_KEY = "turn_hash";
  /**
   * The registry model the router sent the request to (v4 A.3); absent on a
   * pinned request and on entries written before ADR 0015.
   */
  static final String ROUTED_MODEL_KEY = "routed_model";

  /**
   * Candidates fetched per lookup. At least two, so the runner-up's score — the
   * implicit margin behind a hit — exists to be recorded: 0.93 against 0.92 is
   * a coin flip, 0.93 against 0.41 is not.
   */
  private static final int MIN_TOP_K = 2;

  private static final Logger LOG =
      LoggerFactory.getLogger(SemanticCacheAdvisor.class);

  private final VectorStore vectorStore;
  private final SemanticCacheProperties properties;
  private final CacheDecisionTracer tracer;
  private final CalibrationUseCase calibrations;
  private final ModelRegistry modelRegistry;
  private final EmbeddingWindow embeddingWindow;
  private final ConversationAffinityStore conversations;

  SemanticCacheAdvisor(VectorStore vectorStore,
                       SemanticCacheProperties properties,
                       CacheDecisionTracer tracer,
                       CalibrationUseCase calibrations,
                       ModelRegistry modelRegistry,
                       EmbeddingWindow embeddingWindow,
                       ConversationAffinityStore conversations) {
    this.vectorStore = vectorStore;
    this.properties = properties;
    this.tracer = tracer;
    this.calibrations = calibrations;
    this.modelRegistry = modelRegistry;
    this.embeddingWindow = embeddingWindow;
    this.conversations = conversations;
  }

  @Override
  public ChatClientResponse adviseCall(ChatClientRequest request,
                                       CallAdvisorChain chain) {
    CacheLookup lookup = describe(request);
    if (lookup.bypass() != null) {
      tracer.bypassed(lookup.userText(), lookup.bypass(), lookup.scope());
      return CachedResponses.withOutcome(chain.nextCall(request), CacheOutcome.BYPASS);
    }

    List<Document> candidates;
    try {
      candidates = search(lookup);
    } catch (RuntimeException e) {
      LOG.warn("Cache lookup failed ({}), treating as a miss", e.toString());
      tracer.failed(lookup.userText(), activeThreshold(), lookup.scope());
      return CachedResponses.withOutcome(chain.nextCall(request), CacheOutcome.MISS);
    }

    Verdict verdict = decide(candidates, lookup);
    tracer.decided(lookup.userText(), candidates, verdict.hit(), verdict.threshold(),
        verdict.status(), verdict.reason(), lookup.scope());

    if (verdict.hit() != null) {
      LOG.info("Cache HIT for query [{}] (score={})",
          truncate(lookup.userText()), verdict.hit().getScore());
      startConversation(request, verdict.hit());
      return CachedResponses.call(verdict.hit(), request.context());
    }

    LOG.info("Cache MISS for query [{}] ({})",
        truncate(lookup.userText()), verdict.status());

    ChatClientResponse response = chain.nextCall(request);
    cacheStore(lookup, response);
    return CachedResponses.withOutcome(response, CacheOutcome.MISS);
  }

  @Override
  public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,
                                               StreamAdvisorChain chain) {
    CacheLookup lookup = describe(request);
    if (lookup.bypass() != null) {
      tracer.bypassed(lookup.userText(), lookup.bypass(), lookup.scope());
      return chain.nextStream(request);
    }

    // similaritySearch runs eagerly here (Scoped Value still bound), so the
    // per-client filter is applied; the deferred store below captures clientId.
    List<Document> candidates;
    try {
      candidates = search(lookup);
    } catch (RuntimeException e) {
      LOG.warn("Cache lookup failed ({}), treating as a miss", e.toString());
      tracer.failed(lookup.userText(), activeThreshold(), lookup.scope());
      return chain.nextStream(request);
    }

    Verdict verdict = decide(candidates, lookup);
    tracer.decided(lookup.userText(), candidates, verdict.hit(), verdict.threshold(),
        verdict.status(), verdict.reason(), lookup.scope());

    if (verdict.hit() != null) {
      LOG.info("Cache HIT (stream) for query [{}] (score={})",
          truncate(lookup.userText()), verdict.hit().getScore());
      startConversation(request, verdict.hit());
      return CachedResponses.stream(verdict.hit(), request.context());
    }

    LOG.info("Cache MISS (stream) for query [{}] ({})",
        truncate(lookup.userText()), verdict.status());
    String clientId = boundClientId();
    String correlationId = boundCorrelationId();
    StringBuilder aggregate = new StringBuilder();
    AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
    AtomicReference<String> finishReason = new AtomicReference<>();

    return chain.nextStream(request)
        .doOnNext(response -> {
          ChatResponse cr = response.chatResponse();
          if (cr != null) {
            lastResponse.set(cr);
            String delta = deltaText(cr);
            if (delta != null) {
              aggregate.append(delta);
            }
            String reason = finishReasonOf(cr);
            if (reason != null && !reason.isBlank()) {
              finishReason.set(reason);
            }
          }
        })
        .doOnComplete(() -> storeStreamed(lookup, aggregate.toString(),
            lastResponse.get(), finishReason.get(), clientId, correlationId));
  }

  /**
   * Records the conversation a cached first turn opens (ADR 0015): the router
   * never sees a hit, so without this a conversation that began on a cached
   * answer would have no record, and its second turn would fall back to the
   * first-turn floor. The model is the one the stored answer was routed to;
   * an entry that has none — pinned, or older than ADR 0015 — records nothing.
   * Never fails the hit.
   */
  private void startConversation(ChatClientRequest request, Document hit) {
    try {
      Map<String, Object> metadata = hit.getMetadata();
      if (!(metadata.get(ROUTED_MODEL_KEY) instanceof String modelId)
          || !(metadata.get(CACHE_RESPONSE_KEY) instanceof String answer)) {
        return;
      }
      List<ConversationOpening.Turn> turns = request.prompt().getInstructions().stream()
          .map(message -> new ConversationOpening.Turn(
              message.getMessageType().getValue(), message.getText()))
          .toList();
      modelRegistry.findByModelId(modelId).ifPresent(model ->
          ConversationOpening.ofFirstTurn(turns, answer).ifPresent(opening ->
              conversations.record(opening.fingerprint(), modelId, model.tier())));
    } catch (RuntimeException e) {
      LOG.warn("Could not record the conversation of a cached answer: {}", e.toString());
    }
  }

  private CacheLookup describe(ChatClientRequest request) {
    return CacheLookup.describe(request, properties.getMaxHistoryMessages(), modelRegistry,
        embeddingWindow);
  }

  @Override
  public String getName() {
    return "SemanticCache";
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  /**
   * Fetches the nearest candidates in the request's scope, <b>without</b> a
   * store-side threshold.
   *
   * <p>The accept/reject comparison moved here (v2 batch 2) on purpose: filtered
   * out in the store, a rejected candidate is invisible, and neither the
   * runner-up margin nor the near-misses that batch 3 calibrates on would ever
   * be observable. The store now ranks, the advisor decides.
   */
  private List<Document> search(CacheLookup lookup) {
    SearchRequest.Builder searchBuilder = SearchRequest.builder()
        .query(lookup.userText())
        .topK(Math.max(MIN_TOP_K, properties.getTopK()))
        .similarityThresholdAll()
        .filterExpression(buildFilterExpression(lookup.scope(),
            lookup.exactOnly() ? lookup.turnHash() : null));
    return vectorStore.similaritySearch(searchBuilder.build());
  }

  /** The threshold in force, for a lookup that failed before deciding. */
  private double activeThreshold() {
    return calibrations.state(CalibrationTarget.CACHE).effectiveThreshold();
  }

  /**
   * The prediction set, then the request's own limits: a candidate whose stored
   * answer is longer than the request's {@code max_tokens} is not served.
   */
  private Verdict decide(List<Document> candidates, CacheLookup lookup) {
    Verdict verdict = decide(candidates);
    CacheDecisionReason reason =
        lookup.exactOnly() ? CacheDecisionReason.EXACT_MATCH_ONLY : null;
    if (verdict.hit() != null && !fitsMaxTokens(verdict.hit(), lookup.maxTokens())) {
      return new Verdict(null, verdict.threshold(), verdict.status(),
          CacheDecisionReason.MAX_TOKENS);
    }
    return new Verdict(verdict.hit(), verdict.threshold(), verdict.status(), reason);
  }

  /**
   * Applies the conformal prediction set to the candidates (v2 batch 3).
   *
   * <p>Under a valid calibration the threshold is {@code q̂}, fitted so that at
   * most α of the pairs a human judged wrong are served, and the <b>size</b> of
   * the set decides:
   *
   * <ul>
   *   <li>empty — nothing is close enough: a miss;</li>
   *   <li>one — serve it;</li>
   *   <li>more than one — <b>do not serve</b>. If two stored answers both look
   *       right for this query, at most one of them is, and taking the higher
   *       score is guessing with the user's answer. Ambiguity is a risk signal,
   *       not a tie to break.</li>
   * </ul>
   *
   * <p>With no calibration in force this degrades to exactly the previous
   * behaviour — fixed threshold, best candidate wins — because a gateway that
   * has never been calibrated must keep working, and because changing the
   * serving rule for uncalibrated installs would be a behaviour change smuggled
   * in under a feature.
   */
  private Verdict decide(List<Document> candidates) {
    CalibrationState calibration = calibrations.state(CalibrationTarget.CACHE);
    double threshold = calibration.effectiveThreshold();

    List<Document> set = candidates == null ? List.of() : candidates.stream()
        .filter(candidate -> admits(candidate, threshold))
        .toList();

    if (!calibration.isApplied()) {
      ConformalStatus status = calibration.status() == CalibrationStatus.STALE
          ? ConformalStatus.STALE_CALIBRATION : ConformalStatus.NOT_CALIBRATED;
      return new Verdict(set.isEmpty() ? null : set.getFirst(), threshold, status, null);
    }

    return switch (set.size()) {
      case 0 -> new Verdict(null, threshold, ConformalStatus.EMPTY_SET, null);
      case 1 -> new Verdict(set.getFirst(), threshold, ConformalStatus.SINGLETON, null);
      default -> new Verdict(null, threshold, ConformalStatus.AMBIGUOUS, null);
    };
  }

  /** An unscored candidate is not admitted: absence of a score is not a match. */
  private static boolean admits(Document candidate, double threshold) {
    Double score = candidate.getScore();
    return score != null && score >= threshold;
  }

  /**
   * An unknown stored length (no usage was reported when it was stored) fits:
   * refusing it would refuse every answer a provider did not count.
   */
  private static boolean fitsMaxTokens(Document hit, Integer maxTokens) {
    if (maxTokens == null) {
      return true;
    }
    return intOrZero(hit.getMetadata().get(CACHE_COMPLETION_TOKENS_KEY)) <= maxTokens;
  }

  /**
   * The lookup filter: the scope always (so an entry written before ADR 0014,
   * which has none, can never match), the client when namespacing is on, the
   * TTL when one is set, and the full turn's hash when only an exact match is
   * allowed.
   *
   * @param turnHash the hash to match exactly, or null for a similarity lookup
   */
  Filter.Expression buildFilterExpression(String scope, String turnHash) {
    FilterExpressionBuilder b = new FilterExpressionBuilder();
    FilterExpressionBuilder.Op combined = b.eq(CACHE_SCOPE_KEY, scope);

    if (properties.isClientNamespacing() && RequestContext.CURRENT.isBound()) {
      String clientId = RequestContext.CURRENT.get().clientId();
      if (clientId != null) {
        combined = b.and(combined, b.eq(CLIENT_ID_KEY, clientId));
      }
    }

    if (properties.getTtlMinutes() > 0) {
      long cutoff = Instant.now()
          .minus(Duration.ofMinutes(properties.getTtlMinutes()))
          .toEpochMilli();
      combined = b.and(combined, b.gte(CREATED_AT_KEY, cutoff));
    }

    if (turnHash != null) {
      combined = b.and(combined, b.eq(TURN_HASH_KEY, turnHash));
    }

    return combined.build();
  }

  private void cacheStore(CacheLookup lookup, ChatClientResponse response) {
    ChatResponse chatResponse = response.chatResponse();
    if (chatResponse == null) {
      return;
    }
    String text = deltaText(chatResponse);
    store(lookup, text, chatResponse, finishReasonOf(chatResponse), boundClientId(),
        boundCorrelationId());
  }

  /** Stores a streamed miss once aggregated (clientId captured up the stack). */
  private void storeStreamed(CacheLookup lookup, String responseText, ChatResponse lastResponse,
                             String finishReason, String clientId, String correlationId) {
    if (lastResponse == null) {
      return;
    }
    store(lookup, responseText, lastResponse, finishReason, clientId, correlationId);
  }

  /**
   * Writes an answer to the cache — only one that ended normally and says
   * something (ADR 0014): an answer cut by a token limit, a tool call or a
   * filtered answer is not what the next asker should get.
   */
  private void store(CacheLookup lookup, String responseText, ChatResponse response,
                     String providerFinishReason, String clientId, String correlationId) {
    String finishReason = FinishReason.toOpenAi(providerFinishReason);
    if (responseText == null || responseText.isEmpty()
        || !FinishReason.STOP.equals(finishReason)) {
      LOG.debug("Not caching an answer that ended with [{}]", providerFinishReason);
      return;
    }

    Map<String, Object> metadata = new HashMap<>();
    metadata.put(CACHE_RESPONSE_KEY, responseText);
    metadata.put(CREATED_AT_KEY, Instant.now().toEpochMilli());
    metadata.put(CACHE_FINISH_REASON_KEY, finishReason);
    metadata.put(CACHE_SCOPE_KEY, lookup.scope());
    metadata.put(TURN_HASH_KEY, lookup.turnHash());

    ChatResponseMetadata responseMetadata = response.getMetadata();
    if (responseMetadata != null && responseMetadata.getModel() != null) {
      metadata.put(CACHE_MODEL_KEY, responseMetadata.getModel());
    }
    if (responseMetadata != null
        && responseMetadata.get(LlmResponse.ROUTED_MODEL_METADATA_KEY) instanceof String routed) {
      metadata.put(ROUTED_MODEL_KEY, routed);
    }
    if (responseMetadata != null && responseMetadata.getUsage() != null) {
      Usage usage = responseMetadata.getUsage();
      metadata.put(CACHE_PROMPT_TOKENS_KEY, intOrZero(usage.getPromptTokens()));
      metadata.put(CACHE_COMPLETION_TOKENS_KEY,
          intOrZero(usage.getCompletionTokens()));
    }
    if (clientId != null) {
      metadata.put(CLIENT_ID_KEY, clientId);
    }
    if (correlationId != null) {
      // Stamped so a future hit can be traced back to the request whose
      // routing decision produced this answer (v2 batch 2).
      metadata.put(CORRELATION_ID_KEY, correlationId);
    }
    vectorStore.add(List.of(new Document(lookup.userText(), metadata)));
  }

  private static String deltaText(ChatResponse chatResponse) {
    Generation result = chatResponse.getResult();
    if (result == null) {
      return null;
    }
    AssistantMessage output = result.getOutput();
    return output != null ? output.getText() : null;
  }

  private static String finishReasonOf(ChatResponse chatResponse) {
    Generation result = chatResponse.getResult();
    return result == null || result.getMetadata() == null
        ? null : result.getMetadata().getFinishReason();
  }

  private static String boundClientId() {
    return RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get().clientId() : null;
  }

  private static String boundCorrelationId() {
    return RequestContext.CURRENT.isBound()
        ? RequestContext.CURRENT.get().traceId() : null;
  }

  private static int intOrZero(Object value) {
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static String truncate(String text) {
    int maxLen = 80;
    if (text.length() <= maxLen) {
      return text;
    }
    return text.substring(0, maxLen) + "...";
  }

  /**
   * What the cache decided, and under which threshold.
   *
   * @param hit       the entry to serve, or null
   * @param threshold the acceptance threshold in force, calibrated or fixed
   * @param status    the shape of the prediction set, for the trace
   * @param reason    why this was not a plain lookup, or null
   */
  private record Verdict(Document hit, double threshold, ConformalStatus status,
                         CacheDecisionReason reason) {
  }
}
