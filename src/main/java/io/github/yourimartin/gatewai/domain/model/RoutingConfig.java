package io.github.yourimartin.gatewai.domain.model;

import java.util.List;

/**
 * Runtime-tunable routing rules (Phase 5.2): which classification strategy to
 * use, the heuristic thresholds / keywords and the semantic routes. Edited
 * live via the admin API.
 *
 * @param strategy                 classifier strategy: {@code heuristic},
 *                                 {@code embedding} or {@code llm}
 * @param entryLengthThreshold     text longer than this (chars) routes >= entry
 * @param premiumLengthThreshold   text longer than this (chars) routes to premium
 * @param premiumKeywords          substrings that force the premium tier
 * @param routeSimilarityThreshold minimum cosine similarity (0..1) for a
 *                                 semantic route match; below it the heuristic
 *                                 decides
 * @param routes                   semantic routes used by the embedding strategy
 */
public record RoutingConfig(
    String strategy,
    int entryLengthThreshold,
    int premiumLengthThreshold,
    List<String> premiumKeywords,
    double routeSimilarityThreshold,
    List<SemanticRoute> routes
) {

  public RoutingConfig {
    premiumKeywords =
        premiumKeywords == null ? List.of() : List.copyOf(premiumKeywords);
    routes = routes == null ? List.of() : List.copyOf(routes);
  }
}
