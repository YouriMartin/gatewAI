package io.github.yourimartin.gatewai.domain.model;

/**
 * What the conformal prediction set looked like when the cache decided
 * (v2 batch 3).
 *
 * <p>Recorded beside the outcome because the two are not the same thing: a miss
 * caused by an <b>ambiguous</b> set — two stored answers both plausibly matched
 * — is a refusal on purpose, and reads nothing like a miss caused by an empty
 * one. Without this field both are just "MISS".
 */
public enum ConformalStatus {

  /** No candidate reached the calibrated threshold: a miss. */
  EMPTY_SET,

  /** Exactly one candidate did: served. */
  SINGLETON,

  /**
   * More than one did. The cache does <b>not</b> serve: if two stored answers
   * both look right for this query, at most one of them is, and picking the
   * higher score is guessing with the user's answer.
   */
  AMBIGUOUS,

  /**
   * No calibration applied — none stored, or applying them is switched off — so
   * the fixed configured threshold decided.
   */
  NOT_CALIBRATED,

  /**
   * A calibration exists but was fitted against another embedding model, so the
   * fixed threshold decided instead. Degraded, and visible as such.
   */
  STALE_CALIBRATION
}
