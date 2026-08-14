package io.github.yourimartin.gatewai.domain.model;

import java.util.List;
import java.util.Locale;

/**
 * Runtime-tunable routing rules (Phase 5.2): which classification strategy to
 * use, the heuristic thresholds / keywords and the semantic routes. Edited
 * live via the admin API.
 *
 * @param strategy                 classifier strategy: {@code heuristic},
 *                                 {@code embedding}, {@code llm} or
 *                                 {@code cascade}
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

  /**
   * Whether the configured strategy decides by similarity, and so has
   * similarities to explain — the question both explanation services ask first
   * (attribution, batch 7; counterfactuals, batch 8).
   *
   * <p>An unparseable strategy is a "no": explaining something the router may
   * not even be doing is worse than saying the question does not apply.
   */
  public boolean decidesBySimilarity() {
    return switch (parsedStrategy()) {
      case EMBEDDING, CASCADE -> true;
      case null, default -> false;
    };
  }

  /**
   * The routes a semantic strategy can actually match against: a route with no
   * tier has no outcome to offer, and one with no example has nothing to be
   * close to.
   */
  public List<SemanticRoute> usableRoutes() {
    return routes.stream()
        .filter(route -> route.tier() != null && !route.examples().isEmpty())
        .toList();
  }

  private ClassificationStrategy parsedStrategy() {
    if (strategy == null) {
      return null;
    }
    try {
      return ClassificationStrategy.valueOf(
          strategy.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
