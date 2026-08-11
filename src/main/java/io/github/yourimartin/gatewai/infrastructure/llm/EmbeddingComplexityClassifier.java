package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.FallbackCause;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.EmbeddedRoute;
import io.github.yourimartin.gatewai.domain.model.RouteScoring;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * V3 classifier: semantic routes (Aurelio-router style). Each route is a named
 * intent bucket mapped to a tier and described by example prompts. The request
 * is embedded (same local Ollama embedding model as the semantic cache) and
 * compared to every example with cosine similarity; the route holding the
 * closest example wins (max-over-utterances, more robust than centroids for
 * internally diverse routes).
 *
 * <p>Language-independent by construction — similarity is computed in
 * embedding space, not on keywords — and hot-configurable: the route list is
 * re-read per call and the example-embedding index is rebuilt whenever the
 * routes change (admin API edits apply on the next request).
 *
 * <p>When no example reaches {@code route-similarity-threshold}, or the
 * embedding call fails, the heuristic classifier decides, so routing never
 * breaks because the embedding model is unreachable. Those hand-overs are
 * reported as {@link ClassificationJustification.Fallback}, so a degraded
 * decision is never mistaken for a nominal one.
 */
@Component
class EmbeddingComplexityClassifier implements ComplexityClassifier {

  private static final Logger LOG =
      LoggerFactory.getLogger(EmbeddingComplexityClassifier.class);

  private final EmbeddingModel embeddingModel;
  private final ClassifierProperties properties;
  private final HeuristicComplexityClassifier heuristic;
  private final CalibrationUseCase calibrations;

  /** Example-embedding index, rebuilt when the route config changes. */
  private volatile RouteIndex index;

  EmbeddingComplexityClassifier(EmbeddingModel embeddingModel,
                                ClassifierProperties properties,
                                HeuristicComplexityClassifier heuristic,
                                CalibrationUseCase calibrations) {
    this.embeddingModel = embeddingModel;
    this.properties = properties;
    this.heuristic = heuristic;
    this.calibrations = calibrations;
  }

  @Override
  public ClassificationOutcome classify(String userText) {
    if (userText == null || userText.isBlank()) {
      // Not a fallback: no strategy can classify nothing. The heuristic's
      // blank-text rule is the shared short-circuit, reported as itself.
      return heuristic.classify(userText);
    }

    List<SemanticRoute> routes = currentRoutes();
    if (routes.isEmpty()) {
      LOG.debug("No semantic route configured, using heuristic");
      return fallback(userText, FallbackCause.NO_ROUTES_CONFIGURED);
    }

    // Calibrated when one is in force (v2 batch 3), otherwise the configured
    // constant. Batch 5 measured what that constant costs: 82% of English
    // prompts scored below 0.60 and were handed to the heuristic.
    double threshold = calibrations.state(CalibrationTarget.ROUTING)
        .effectiveThreshold();
    try {
      RouteIndex idx = indexFor(routes);
      float[] query = embeddingModel.embed(userText);

      List<ClassificationJustification.RouteCandidate> candidates =
          RouteScoring.rank(query, idx.routes());
      if (candidates.isEmpty()) {
        return fallback(userText, FallbackCause.NO_ROUTES_CONFIGURED);
      }

      ClassificationJustification.RouteCandidate best = candidates.getFirst();
      double margin = candidates.size() > 1
          ? best.score() - candidates.get(1).score() : 0.0;

      if (best.score() < threshold) {
        LOG.debug("No route above threshold (best={} at {}), using heuristic",
            best.route(), best.score());
        return fallback(userText, FallbackCause.BELOW_THRESHOLD,
            candidates, margin, threshold);
      }

      LOG.debug("Route match: {} -> {} ({})",
          best.route(), best.tier(), best.score());
      return new ClassificationOutcome(best.tier(),
          new ClassificationJustification.Embedding(
              candidates, best.score(), margin, threshold));
    } catch (RuntimeException e) {
      LOG.warn("Embedding classification failed ({}), falling back",
          e.getMessage());
      return fallback(userText, FallbackCause.EMBEDDING_ERROR);
    }
  }

  private ClassificationOutcome fallback(String userText, FallbackCause cause) {
    return heuristic.classify(userText)
        .asFallbackFrom(ClassificationStrategy.EMBEDDING, cause);
  }

  /**
   * Below-threshold hand-over: the heuristic decides, and the route scores ride
   * along as evidence. Knowing the best route only reached 0.41 is exactly what
   * explains why the heuristic had to decide — and what batch 3 calibrates.
   */
  private ClassificationOutcome fallback(
      String userText, FallbackCause cause,
      List<ClassificationJustification.RouteCandidate> candidates,
      double margin, double threshold) {

    ClassificationOutcome decided = heuristic.classify(userText);
    return new ClassificationOutcome(decided.tier(),
        new ClassificationJustification.Fallback(
            ClassificationStrategy.EMBEDDING, cause, decided.justification(),
            new ClassificationJustification.Embedding(
                candidates, candidates.getFirst().score(), margin, threshold)));
  }

  private List<SemanticRoute> currentRoutes() {
    return properties.getRoutes().stream()
        .map(route -> new SemanticRoute(
            route.getName(), route.getTier(), route.getExamples()))
        .filter(route -> route.tier() != null && !route.examples().isEmpty())
        .toList();
  }

  /**
   * Returns the cached index when the routes are unchanged (record equality on
   * the snapshot), otherwise re-embeds every example. Concurrent rebuilds are
   * harmless: both threads compute the same index and the last write wins.
   */
  private RouteIndex indexFor(List<SemanticRoute> routes) {
    RouteIndex current = index;
    if (current != null && current.snapshot().equals(routes)) {
      return current;
    }

    List<EmbeddedRoute> indexed = new ArrayList<>();
    for (SemanticRoute route : routes) {
      indexed.add(new EmbeddedRoute(route, embeddingModel.embed(route.examples())));
    }
    RouteIndex fresh = new RouteIndex(routes, List.copyOf(indexed));
    index = fresh;
    LOG.info("Semantic route index rebuilt: {} route(s), {} example(s)",
        routes.size(),
        routes.stream().mapToInt(r -> r.examples().size()).sum());
    return fresh;
  }

  private record RouteIndex(List<SemanticRoute> snapshot,
                            List<EmbeddedRoute> routes) {
  }
}
