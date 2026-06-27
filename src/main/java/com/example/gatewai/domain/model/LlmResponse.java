package com.example.gatewai.domain.model;

/**
 * Result of an LLM call.
 *
 * @param cacheHit {@code true} when the response was served from the semantic
 *     cache instead of a real inference. On a hit the token counts are the
 *     original (replayed) values, but no model was actually invoked.
 */
public record LlmResponse(
    String model,
    String content,
    String finishReason,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    boolean cacheHit
) {

  /**
   * Key under which the semantic-cache advisor flags a cache hit in the
   * {@code ChatResponseMetadata}, so the LLM adapter can propagate it here.
   * Defined in the domain to keep the cache and llm adapters decoupled.
   */
  public static final String CACHE_HIT_METADATA_KEY = "gatewai.cache.hit";
}
