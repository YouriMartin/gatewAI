package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * Token counts of one served request, as the provider reported them (v3 lot C.4).
 *
 * <p>{@code totalTokens} is carried rather than recomputed: it is what billing is
 * based on, and a provider is free to report a total that is not exactly
 * {@code prompt + completion}. Energy uses the two phases, cost uses the total.
 *
 * <p>Since v4 B.6 it also carries the prompt tokens the provider served from its
 * prompt cache, which split pricing bills at a lower rate. They are a part of
 * {@code promptTokens}, as OpenAI reports them; an adapter whose provider counts
 * them apart (Anthropic) adds them back before building this.
 *
 * @param promptTokens       tokens in the prompt (prefill), cached ones included
 * @param completionTokens   tokens generated (decode)
 * @param totalTokens        total billed tokens
 * @param cachedPromptTokens prompt tokens read from the provider's cache;
 *                           {@code null} when the provider did not report them,
 *                           which is not the same as reporting zero
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens,
                         Integer cachedPromptTokens) {

  /** No usage at all — nothing to account. */
  public static final TokenUsage NONE = new TokenUsage(0, 0, 0);

  /** Usage from a provider that reported no cached tokens. */
  public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    this(promptTokens, completionTokens, totalTokens, null);
  }

  /** Usage whose total is simply the sum of its phases. */
  public static TokenUsage of(int promptTokens, int completionTokens) {
    return new TokenUsage(promptTokens, completionTokens,
        promptTokens + completionTokens);
  }
}
