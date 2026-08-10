package io.github.yourimartin.gatewai.domain.model;

/** What the semantic cache did with a request (v2 batch 2). */
public enum CacheOutcome {

  /** A candidate cleared the threshold and its answer was served. */
  HIT,

  /** No candidate cleared the threshold; the request went to a model. */
  MISS,

  /** The cache was not consulted (blank prompt). */
  BYPASS,

  /** The lookup failed; the request went to a model as if it were a miss. */
  ERROR
}
