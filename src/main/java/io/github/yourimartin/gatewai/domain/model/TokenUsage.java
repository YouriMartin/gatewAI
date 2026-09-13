package io.github.yourimartin.gatewai.domain.model;

/**
 * Token counts of one served request, as the provider reported them (v3 lot C.4).
 *
 * <p>{@code totalTokens} is carried rather than recomputed: it is what billing is
 * based on, and a provider is free to report a total that is not exactly
 * {@code prompt + completion}. Energy uses the two phases, cost uses the total.
 *
 * @param promptTokens     tokens in the prompt (prefill)
 * @param completionTokens tokens generated (decode)
 * @param totalTokens      total billed tokens
 */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {

  /** No usage at all — nothing to account. */
  public static final TokenUsage NONE = new TokenUsage(0, 0, 0);

  /** Usage whose total is simply the sum of its phases. */
  public static TokenUsage of(int promptTokens, int completionTokens) {
    return new TokenUsage(promptTokens, completionTokens,
        promptTokens + completionTokens);
  }
}
