package io.github.yourimartin.gatewai.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.yourimartin.gatewai.domain.model.AttributionKey;
import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.model.AttributionStatus;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.EmbeddedRoute;
import io.github.yourimartin.gatewai.domain.model.Occlusion;
import io.github.yourimartin.gatewai.domain.model.PromptHash;
import io.github.yourimartin.gatewai.domain.model.PromptSegment;
import io.github.yourimartin.gatewai.domain.model.PromptSegmentation;
import io.github.yourimartin.gatewai.domain.model.RouteScoring;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.RoutingConfigVersion;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.model.Similarity;
import io.github.yourimartin.gatewai.domain.port.in.PromptAttributionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.AttributionCache;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Attributes a routing decision to the parts of the prompt that caused it
 * (v2 batch 7).
 *
 * <p>The method is occlusion: embed the prompt, find the route it matched, then
 * embed the prompt again with each segment removed and see how much similarity
 * that costs. What a segment costs is what it contributed. There is no access to
 * the embedding model's internals from the JVM, so gradient attribution is off
 * the table; occlusion needs nothing but the port already in use.
 *
 * <p><b>Cost.</b> One embedding per segment plus one, capped at
 * {@code gatewai.attribution.max-segments}, against the same local model that
 * serves requests — the one place in v2 that can visibly load the box. Three
 * things keep it in hand: the cap, the cache (keyed on the prompt, the embedding
 * model <em>and</em> the routing rules), and the fact that nothing calls this
 * unless a human asks.
 *
 * <p>The occluded embeddings run on virtual threads. Not structured concurrency,
 * which is still a preview feature and excluded from this project's core: a
 * plain virtual-thread executor gives the same overlap here, since the tasks are
 * independent and the caller waits for all of them anyway.
 */
@Service
class OcclusionAttributionService implements PromptAttributionUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(OcclusionAttributionService.class);

  /** Strategies that decide by similarity, and so have similarity to explain. */
  private static final Set<String> SEMANTIC_STRATEGIES =
      Set.of("embedding", "cascade");

  private final TextEmbedder embedder;
  private final RoutingConfigPort routingConfig;
  private final AttributionCache cache;
  private final int maxSegments;
  private final int maxSegmentChars;

  /** Example-embedding index, rebuilt when the routes change. */
  private volatile RouteIndex index;

  OcclusionAttributionService(
      TextEmbedder embedder,
      RoutingConfigPort routingConfig,
      AttributionCache cache,
      @Value("${gatewai.attribution.max-segments:20}") int maxSegments,
      @Value("${gatewai.attribution.max-segment-chars:200}") int maxSegmentChars) {
    this.embedder = embedder;
    this.routingConfig = routingConfig;
    this.cache = cache;
    this.maxSegments = maxSegments;
    this.maxSegmentChars = maxSegmentChars;
  }

  @Override
  public AttributionReport attribute(String prompt) {
    RoutingConfig config = routingConfig.get();
    String version = RoutingConfigVersion.of(config);

    if (prompt == null || prompt.isBlank()) {
      return AttributionReport.notComputed(
          AttributionStatus.EMPTY_PROMPT, embedder.modelId(), version);
    }
    if (!isSemantic(config)) {
      // Not a failure: the honest answer is that this decision was not about
      // similarity at all, and saying so beats returning an empty list.
      return AttributionReport.notComputed(
          AttributionStatus.NOT_APPLICABLE_STRATEGY, embedder.modelId(), version);
    }
    List<SemanticRoute> routes = usableRoutes(config);
    if (routes.isEmpty()) {
      return AttributionReport.notComputed(
          AttributionStatus.NO_ROUTES_CONFIGURED, embedder.modelId(), version);
    }

    AttributionKey key = new AttributionKey(
        PromptHash.of(prompt), embedder.modelId(), version);
    return cache.get(key).orElseGet(() -> {
      AttributionReport report = compute(prompt, routes, version);
      cache.put(key, report);
      return report;
    });
  }

  private AttributionReport compute(String prompt, List<SemanticRoute> routes,
                                    String version) {
    RouteIndex routeIndex = indexFor(routes);
    float[] promptVector = embedder.embed(prompt);

    List<ClassificationJustification.RouteCandidate> candidates =
        RouteScoring.rank(promptVector, routeIndex.routes());
    ClassificationJustification.RouteCandidate best = candidates.getFirst();
    float[] utterance = utteranceVector(routeIndex, best);

    List<PromptSegment> segments =
        PromptSegmentation.segment(prompt, maxSegments, maxSegmentChars);
    List<Double> occluded = occludedSimilarities(prompt, segments, utterance);

    LOG.debug("Attributed {} segment(s) of a prompt matched to route {} ({})",
        segments.size(), best.route(), best.score());

    return new AttributionReport(AttributionStatus.COMPUTED, best.route(),
        best.tier(), best.bestUtterance(), best.score(),
        Occlusion.attribute(best.score(), segments, occluded),
        embedder.modelId(), version);
  }

  /**
   * Embeds every occluded variant, in parallel, and scores it against the same
   * utterance the whole prompt matched — the same yardstick throughout, or the
   * differences would not be differences of anything.
   */
  private List<Double> occludedSimilarities(String prompt,
                                            List<PromptSegment> segments,
                                            float[] utterance) {
    if (segments.isEmpty()) {
      return List.of();
    }

    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Double>> futures = new ArrayList<>(segments.size());
      for (PromptSegment segment : segments) {
        String occluded = segment.occlude(prompt);
        futures.add(workers.submit(() -> similarity(occluded, utterance)));
      }

      List<Double> similarities = new ArrayList<>(segments.size());
      for (Future<Double> future : futures) {
        similarities.add(await(future));
      }
      return similarities;
    }
  }

  /**
   * Occluding the only segment leaves nothing, and an empty prompt has no
   * vector: its similarity is 0 by definition rather than by measurement, which
   * makes that segment's contribution the whole similarity — which it is.
   */
  private double similarity(String occluded, float[] utterance) {
    if (occluded.isBlank()) {
      return 0;
    }
    return Similarity.cosine(embedder.embed(occluded), utterance);
  }

  private static double await(Future<Double> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Attribution interrupted", e);
    } catch (ExecutionException e) {
      // On demand, off the request path: an admin asking why is owed the error,
      // not a plausible-looking report built on a failed embedding call.
      throw new IllegalStateException(
          "Could not embed an occluded prompt: " + e.getCause(), e.getCause());
    }
  }

  /** The vector of the example the prompt matched, from the index. */
  private static float[] utteranceVector(
      RouteIndex index, ClassificationJustification.RouteCandidate best) {
    for (EmbeddedRoute embedded : index.routes()) {
      if (embedded.route().name().equals(best.route())) {
        int position = embedded.route().examples().indexOf(best.bestUtterance());
        if (position >= 0) {
          return embedded.exampleVectors().get(position);
        }
      }
    }
    throw new IllegalStateException(
        "The matched utterance is not in the index: " + best.route());
  }

  private static boolean isSemantic(RoutingConfig config) {
    return config.strategy() != null && SEMANTIC_STRATEGIES.contains(
        config.strategy().toLowerCase(Locale.ROOT));
  }

  private static List<SemanticRoute> usableRoutes(RoutingConfig config) {
    return config.routes().stream()
        .filter(route -> route.tier() != null && !route.examples().isEmpty())
        .toList();
  }

  /**
   * The example vectors, re-embedded only when the routes change (record
   * equality on the snapshot, the same trick the classifier's index uses).
   * Rebuilding per call would triple the cost of an attribution for nothing.
   */
  private RouteIndex indexFor(List<SemanticRoute> routes) {
    RouteIndex current = index;
    if (current != null && current.snapshot().equals(routes)) {
      return current;
    }

    List<EmbeddedRoute> embedded = new ArrayList<>(routes.size());
    for (SemanticRoute route : routes) {
      List<float[]> vectors = new ArrayList<>(route.examples().size());
      for (String example : route.examples()) {
        vectors.add(embedder.embed(example));
      }
      embedded.add(new EmbeddedRoute(route, vectors));
    }

    RouteIndex fresh = new RouteIndex(routes, List.copyOf(embedded));
    index = fresh;
    return fresh;
  }

  private record RouteIndex(List<SemanticRoute> snapshot,
                            List<EmbeddedRoute> routes) {
  }
}
