package io.github.yourimartin.gatewai.domain.model.decision;

/**
 * Why a cache decision is not a plain similarity lookup (ADR 0014, v4 A.2).
 * Null on an ordinary {@code HIT} or {@code MISS}.
 */
public enum CacheDecisionReason {

  /** {@code BYPASS}: no user text to look up. */
  EMPTY_PROMPT,

  /** {@code BYPASS}: more non-system messages than {@code gatewai.cache.max-history-messages}. */
  HISTORY_TOO_LONG,

  /**
   * {@code HIT} or {@code MISS}: the last user turn is longer than the embedding
   * window, so only an identical turn could match.
   */
  EXACT_MATCH_ONLY,

  /** {@code MISS}: the candidate's stored answer is longer than the request's {@code max_tokens}. */
  MAX_TOKENS
}
