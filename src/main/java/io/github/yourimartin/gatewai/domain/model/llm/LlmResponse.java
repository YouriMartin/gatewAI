package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * Result of an LLM call.
 *
 * @param cacheHit {@code true} when the response was served from the semantic
 *     cache instead of a real inference. On a hit the token counts are the
 *     original (replayed) values, but no model was actually invoked.
 * @param cacheOutcome what the cache did — {@code HIT}, {@code MISS} or
 *     {@code BYPASS} — for the {@code X-GatewAI-Cache} header (ADR 0014); null
 *     when no cache decision reached the response
 */
public record LlmResponse(
    String model,
    String content,
    String finishReason,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    boolean cacheHit,
    String cacheOutcome
) {

  /**
   * Key under which the semantic-cache advisor flags a cache hit in the
   * {@code ChatResponseMetadata}, so the LLM adapter can propagate it here.
   * Defined in the domain to keep the cache and llm adapters decoupled.
   */
  public static final String CACHE_HIT_METADATA_KEY = "gatewai.cache.hit";

  /** Key under which the cache advisor records its outcome in the response metadata. */
  public static final String CACHE_OUTCOME_METADATA_KEY = "gatewai.cache.outcome";

  /**
   * Key under which the router records the <b>registry</b> model id it sent the
   * request to (v4 A.3) — the provider reports its own name, often a dated
   * variant. The cache stores it, so a cached first turn starts a conversation
   * on a model the registry knows.
   */
  public static final String ROUTED_MODEL_METADATA_KEY = "gatewai.routing.model";

  /** A response whose cache outcome is only known as hit or not. */
  public LlmResponse(String model, String content, String finishReason, int promptTokens,
                     int completionTokens, int totalTokens, boolean cacheHit) {
    this(model, content, finishReason, promptTokens, completionTokens, totalTokens, cacheHit,
        cacheHit ? "HIT" : null);
  }
}
