package io.github.yourimartin.gatewai.domain.model.decision;

import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;

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
  MAX_TOKENS,

  /*
   * BYPASS on the pass-through path (ADR 0016, v4 B.3): the request uses a
   * feature the advisor chain cannot honour, and the cache was never consulted.
   * One reason per PassThroughFeature, named after it.
   */

  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#TOOLS}. */
  PASSTHROUGH_TOOLS,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#TOOL_MESSAGES}. */
  PASSTHROUGH_TOOL_MESSAGES,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#IMAGE_INPUT}. */
  PASSTHROUGH_IMAGE_INPUT,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#AUDIO_INPUT}. */
  PASSTHROUGH_AUDIO_INPUT,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#FILE_INPUT}. */
  PASSTHROUGH_FILE_INPUT,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#OTHER_CONTENT}. */
  PASSTHROUGH_OTHER_CONTENT,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#RESPONSE_FORMAT}. */
  PASSTHROUGH_RESPONSE_FORMAT,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#MULTIPLE_CHOICES}. */
  PASSTHROUGH_MULTIPLE_CHOICES,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#LOGPROBS}. */
  PASSTHROUGH_LOGPROBS,
  /** {@code BYPASS}: pass-through for {@link PassThroughFeature#LOGIT_BIAS}. */
  PASSTHROUGH_LOGIT_BIAS;

  /** The bypass reason recorded for a request passed through for {@code feature}. */
  public static CacheDecisionReason passThrough(PassThroughFeature feature) {
    return valueOf("PASSTHROUGH_" + feature.name());
  }
}
