package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * One piece of a streamed LLM response (Phase 7.5). Intermediate chunks carry a
 * {@code contentDelta}; the terminal chunk ({@code last == true}) also carries
 * the finish reason and token usage. {@code model} is the model that actually
 * served the request (the routed one). Pure domain — no reactive types here; the
 * reactive plumbing lives in the infrastructure adapters.
 *
 * <p>{@code cachedPromptTokens} (v4 B.6) is the part of {@code promptTokens} the
 * provider served from its prompt cache, {@code null} when it did not say.
 */
public record LlmStreamChunk(
    String model,
    String contentDelta,
    String finishReason,
    boolean cacheHit,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    boolean last,
    Integer cachedPromptTokens
) {

  /** A chunk whose provider reported no cached prompt tokens. */
  public LlmStreamChunk(String model, String contentDelta, String finishReason,
                        boolean cacheHit, int promptTokens, int completionTokens,
                        int totalTokens, boolean last) {
    this(model, contentDelta, finishReason, cacheHit, promptTokens, completionTokens,
        totalTokens, last, null);
  }
}
