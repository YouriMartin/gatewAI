package io.github.yourimartin.gatewai.domain.model;

import java.util.List;

/**
 * Where a request would have gone instead, and how close it came (v2 batch 8).
 *
 * <p>The companion of {@link AttributionReport}: attribution explains the
 * <em>match</em> — which words carried the similarity to the winning route —
 * and this explains the <em>ranking</em>, which is what actually picked a tier
 * among several. Both decompose the same numbers, so both name the route and the
 * utterance they are talking about; neither is readable without that.
 *
 * @param status               whether alternatives were ranked, or why not
 * @param chosenRoute          the route that won, null unless a route won
 * @param chosenTier           the tier it maps to
 * @param chosenUtterance      the example the request was closest to
 * @param chosenSimilarity     that similarity — the reference every {@code gap}
 *                             is measured from
 * @param alternatives         the near misses, closest first
 * @param embeddingModel       provenance: the model behind the vectors
 * @param routingConfigVersion provenance: the rules the ranking was produced
 *                             against, since editing a route changes it
 */
public record CounterfactualReport(
    CounterfactualStatus status,
    String chosenRoute,
    ModelTier chosenTier,
    String chosenUtterance,
    double chosenSimilarity,
    List<Counterfactual> alternatives,
    String embeddingModel,
    String routingConfigVersion
) {

  public CounterfactualReport {
    alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
  }

  /** A report that explains why there is nothing to compare. */
  public static CounterfactualReport notComputed(CounterfactualStatus status,
                                                 String embeddingModel,
                                                 String routingConfigVersion) {
    return new CounterfactualReport(status, null, null, null, 0, List.of(),
        embeddingModel, routingConfigVersion);
  }
}
