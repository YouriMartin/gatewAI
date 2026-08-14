package io.github.yourimartin.gatewai.application.service;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.EmbeddedRoute;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The route examples, embedded once and re-embedded only when the routes change
 * — shared by every explanation service (attribution, batch 7; counterfactuals,
 * batch 8).
 *
 * <p>Shared rather than copied per service for a reason that is not tidiness:
 * batch 9's explain endpoint answers both questions about the same prompt, and
 * two private indexes would embed every configured example twice and hold two
 * copies of the result. The classifier keeps its own index on purpose — it sits
 * on the request path, reads a different configuration source and must not have
 * its latency coupled to an admin tool.
 *
 * <p><b>The vectors here come from route configuration only.</b> Nothing a
 * client sent is ever indexed, which is what lets an explanation quote an
 * utterance back to an operator without quoting another user's prompt.
 */
@Component
class SemanticRouteIndex {

  private static final Logger LOG =
      LoggerFactory.getLogger(SemanticRouteIndex.class);

  private final TextEmbedder embedder;

  /** Last built index; replaced wholesale, never mutated. */
  private volatile Snapshot current;

  SemanticRouteIndex(TextEmbedder embedder) {
    this.embedder = embedder;
  }

  /**
   * The embedded form of {@code routes}, rebuilt only when they differ from the
   * ones last indexed (record equality on the snapshot). Concurrent rebuilds are
   * harmless: both threads compute the same vectors and the last write wins.
   */
  List<EmbeddedRoute> forRoutes(List<SemanticRoute> routes) {
    Snapshot snapshot = current;
    if (snapshot != null && snapshot.routes().equals(routes)) {
      return snapshot.embedded();
    }

    List<EmbeddedRoute> embedded = new ArrayList<>(routes.size());
    for (SemanticRoute route : routes) {
      List<float[]> vectors = new ArrayList<>(route.examples().size());
      for (String example : route.examples()) {
        vectors.add(embedder.embed(example));
      }
      embedded.add(new EmbeddedRoute(route, vectors));
    }

    List<EmbeddedRoute> indexed = List.copyOf(embedded);
    current = new Snapshot(List.copyOf(routes), indexed);
    LOG.debug("Explanation route index rebuilt: {} route(s), {} example(s)",
        routes.size(),
        routes.stream().mapToInt(route -> route.examples().size()).sum());
    return indexed;
  }

  /**
   * The vector of one route's example, by identity of both — the yardstick an
   * occlusion measures against.
   *
   * @throws IllegalStateException if the pair is not indexed, which would mean
   *                               the ranking and the index disagree
   */
  static float[] vectorOf(List<EmbeddedRoute> index, String route,
                          String utterance) {
    for (EmbeddedRoute embedded : index) {
      if (embedded.route().name().equals(route)) {
        int position = embedded.route().examples().indexOf(utterance);
        if (position >= 0) {
          return embedded.exampleVectors().get(position);
        }
      }
    }
    throw new IllegalStateException(
        "The matched utterance is not in the index: " + route);
  }

  private record Snapshot(List<SemanticRoute> routes,
                          List<EmbeddedRoute> embedded) {
  }
}
