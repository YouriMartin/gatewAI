package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
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
 * breaks because the embedding model is unreachable.
 */
@Component
class EmbeddingComplexityClassifier implements ComplexityClassifier {

  private static final Logger LOG =
      LoggerFactory.getLogger(EmbeddingComplexityClassifier.class);

  private final EmbeddingModel embeddingModel;
  private final ClassifierProperties properties;
  private final HeuristicComplexityClassifier heuristic;

  /** Example-embedding index, rebuilt when the route config changes. */
  private volatile RouteIndex index;

  EmbeddingComplexityClassifier(EmbeddingModel embeddingModel,
                                ClassifierProperties properties,
                                HeuristicComplexityClassifier heuristic) {
    this.embeddingModel = embeddingModel;
    this.properties = properties;
    this.heuristic = heuristic;
  }

  @Override
  public ModelTier classify(String userText) {
    if (userText == null || userText.isBlank()) {
      return ModelTier.LOCAL;
    }

    List<SemanticRoute> routes = currentRoutes();
    if (routes.isEmpty()) {
      LOG.debug("No semantic route configured, using heuristic");
      return heuristic.classify(userText);
    }

    try {
      RouteIndex idx = indexFor(routes);
      float[] query = embeddingModel.embed(userText);

      RouteMatch best = bestMatch(idx, query);
      if (best == null
          || best.similarity() < properties.getRouteSimilarityThreshold()) {
        LOG.debug("No route above threshold (best={}), using heuristic",
            best);
        return heuristic.classify(userText);
      }

      LOG.debug("Route match: {}", best);
      return best.tier();
    } catch (RuntimeException e) {
      LOG.warn("Embedding classification failed ({}), falling back",
          e.getMessage());
      return heuristic.classify(userText);
    }
  }

  private static RouteMatch bestMatch(RouteIndex idx, float[] query) {
    RouteMatch best = null;
    for (IndexedRoute route : idx.routes()) {
      for (float[] example : route.vectors()) {
        double similarity = cosineSimilarity(query, example);
        if (best == null || similarity > best.similarity()) {
          best = new RouteMatch(route.name(), route.tier(), similarity);
        }
      }
    }
    return best;
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

    List<IndexedRoute> indexed = new ArrayList<>();
    for (SemanticRoute route : routes) {
      indexed.add(new IndexedRoute(route.name(), route.tier(),
          embeddingModel.embed(route.examples())));
    }
    RouteIndex fresh = new RouteIndex(routes, List.copyOf(indexed));
    index = fresh;
    LOG.info("Semantic route index rebuilt: {} route(s), {} example(s)",
        routes.size(),
        routes.stream().mapToInt(r -> r.examples().size()).sum());
    return fresh;
  }

  private static double cosineSimilarity(float[] a, float[] b) {
    double dot = 0;
    double normA = 0;
    double normB = 0;
    int length = Math.min(a.length, b.length);
    for (int i = 0; i < length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  private record RouteIndex(List<SemanticRoute> snapshot,
                            List<IndexedRoute> routes) {
  }

  private record IndexedRoute(String name, ModelTier tier,
                              List<float[]> vectors) {
  }

  private record RouteMatch(String route, ModelTier tier, double similarity) {

    @Override
    public String toString() {
      return "%s -> %s (%.3f)".formatted(route, tier, similarity);
    }
  }
}
