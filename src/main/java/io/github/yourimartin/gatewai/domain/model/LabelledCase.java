package io.github.yourimartin.gatewai.domain.model;

/**
 * The labelled data a calibration is fitted on (v2 batch 3).
 *
 * <p>Two shapes, one per decision. Deliberately minimal: the evaluation
 * datasets carry ids, languages and tags for reporting, none of which a
 * quantile has any use for.
 */
public sealed interface LabelledCase {

  /**
   * A prompt and the tier a human says it needs.
   *
   * @param prompt       the request text
   * @param expectedTier the label
   */
  record Routing(String prompt, ModelTier expectedTier) implements LabelledCase {
  }

  /**
   * A query, an entry already in the cache, and whether serving that entry's
   * answer would be correct.
   *
   * @param query    the incoming request
   * @param entry    the request that produced the cached answer
   * @param servable true when the cached answer would correctly answer the query
   */
  record CachePair(String query, String entry, boolean servable)
      implements LabelledCase {
  }
}
