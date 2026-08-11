package io.github.yourimartin.gatewai.application.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.ConformalQuantile;
import io.github.yourimartin.gatewai.domain.model.EmbeddedRoute;
import io.github.yourimartin.gatewai.domain.model.LabelledCase;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.RouteScoring;
import io.github.yourimartin.gatewai.domain.model.RoutingConfigVersion;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.model.Similarity;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CalibrationStore;
import io.github.yourimartin.gatewai.domain.port.out.LabelledCaseSource;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fits, stores and serves the conformal calibrations (v2 batch 3).
 *
 * <p>Two thresholds that used to be constants — the cache's 0.92 and the
 * router's 0.60 — become quantiles of labelled data, each with a stated
 * guarantee. Everything about <i>how</i> the quantile is computed lives in
 * {@link ConformalQuantile}; this class is the plumbing that produces the scores
 * and decides when a stored calibration may still be applied.
 *
 * <p>The routing scores are produced by {@link RouteScoring}, the same domain
 * code the router itself ranks with. Sharing the scoring rather than calling the
 * router is what keeps the calibration fitted on exactly the numbers the router
 * decides with — and, unlike asking the router directly, it does not make the
 * calibration and the classifier depend on each other.
 */
@Service
class ConformalCalibrationService implements CalibrationUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConformalCalibrationService.class);

  /**
   * How long a loaded snapshot is trusted before the store is read again. Long
   * enough that the hot path never queues on a query, short enough that a
   * recalibration on another instance is picked up without a restart.
   */
  private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(60);

  private final CalibrationStore store;
  private final LabelledCaseSource labelledCases;
  private final TextEmbedder embedder;
  private final RoutingConfigPort routingConfig;
  private final boolean enabled;
  private final double defaultRoutingAlpha;
  private final double defaultCacheAlpha;
  private final double fixedCacheThreshold;

  private volatile Snapshot snapshot;
  private volatile ConfigVersion configVersion;
  private final Map<CalibrationTarget, CalibrationStatus> lastLoggedStatus =
      new EnumMap<>(CalibrationTarget.class);

  ConformalCalibrationService(
      CalibrationStore store,
      LabelledCaseSource labelledCases,
      TextEmbedder embedder,
      RoutingConfigPort routingConfig,
      @Value("${gatewai.conformal.enabled:true}") boolean enabled,
      @Value("${gatewai.conformal.routing-alpha:0.10}") double defaultRoutingAlpha,
      @Value("${gatewai.conformal.cache-alpha:0.10}") double defaultCacheAlpha,
      @Value("${gatewai.cache.similarity-threshold:0.92}") double fixedCacheThreshold) {
    this.store = store;
    this.labelledCases = labelledCases;
    this.embedder = embedder;
    this.routingConfig = routingConfig;
    this.enabled = enabled;
    this.defaultRoutingAlpha = defaultRoutingAlpha;
    this.defaultCacheAlpha = defaultCacheAlpha;
    this.fixedCacheThreshold = fixedCacheThreshold;
  }

  @Override
  public CalibrationState state(CalibrationTarget target) {
    ConformalCalibration stored = current().get(target);
    CalibrationStatus status =
        stored == null ? CalibrationStatus.ABSENT : statusOf(stored);
    logStatusChange(target, status);
    return new CalibrationState(target, status, stored, fixedFallback(target));
  }

  @Override
  public List<CalibrationState> states() {
    List<CalibrationState> states = new ArrayList<>();
    for (CalibrationTarget target : CalibrationTarget.values()) {
      states.add(state(target));
    }
    return List.copyOf(states);
  }

  /** Stale beats disabled: an operator turning it off should still see it rotted. */
  private CalibrationStatus statusOf(ConformalCalibration calibration) {
    CalibrationStatus status =
        calibration.statusFor(embedder.modelId(), routingConfigVersion());
    if (status == CalibrationStatus.VALID && !enabled) {
      return CalibrationStatus.DISABLED;
    }
    return status;
  }

  @Override
  public List<CalibrationState> recalibrate(Double routingAlpha, Double cacheAlpha) {
    double alphaRouting = routingAlpha == null ? defaultRoutingAlpha : routingAlpha;
    double alphaCache = cacheAlpha == null ? defaultCacheAlpha : cacheAlpha;

    Instant startedAt = Instant.now();
    ConformalCalibration routing = calibrateRouting(alphaRouting);
    ConformalCalibration cache = calibrateCache(alphaCache);

    store.save(routing);
    store.save(cache);
    snapshot = null;

    LOG.info("Recalibrated in {}s from {}: routing threshold {} (alpha={}, n={}), "
            + "cache threshold {} (alpha={}, n={})",
        Duration.between(startedAt, Instant.now()).toSeconds(),
        labelledCases.description(),
        routing.similarityThreshold(), routing.alpha(), routing.sampleSize(),
        cache.similarityThreshold(), cache.alpha(), cache.sampleSize());
    return states();
  }

  /**
   * Routing: non-conformity is {@code 1 − similarity} to the closest example of
   * a route mapped to the labelled tier. A prompt whose correct route is far
   * away is the case the threshold must not exclude.
   */
  private ConformalCalibration calibrateRouting(double alpha) {
    List<EmbeddedRoute> routes = embedRoutes();
    List<LabelledCase.Routing> cases = labelledCases.routingCases();
    List<Double> scores = new ArrayList<>();
    int unusable = 0;

    for (LabelledCase.Routing labelled : cases) {
      OptionalDouble best = RouteScoring.bestScoreFor(labelled.expectedTier(),
          RouteScoring.rank(embedder.embed(labelled.prompt()), routes));
      if (best.isPresent()) {
        scores.add(1 - best.getAsDouble());
      } else {
        unusable++;
      }
    }

    if (scores.isEmpty()) {
      throw new IllegalStateException(
          "No labelled prompt produced route scores (" + unusable + " unusable). "
              + "Check that the semantic routes cover the tiers the labels use.");
    }

    double qhat = quantileOrFail(scores, alpha, CalibrationTarget.ROUTING);
    if (unusable > 0) {
      LOG.warn("{} of {} labelled prompts had no route for their tier and were skipped",
          unusable, cases.size());
    }
    return new ConformalCalibration(CalibrationTarget.ROUTING,
        ConformalGuarantee.CORRECT_TARGET_COVERAGE, alpha, qhat, scores.size(),
        embedder.modelId(), routingConfigVersion(), Instant.now());
  }

  /**
   * Cache: fitted on the pairs a human judged <b>not</b> servable, so {@code α}
   * bounds the rate at which another question's answer is returned. See
   * {@link ConformalGuarantee} for why this side and not the other.
   */
  private ConformalCalibration calibrateCache(double alpha) {
    Map<String, float[]> embeddings = new HashMap<>();
    List<Double> negatives = new ArrayList<>();

    for (LabelledCase.CachePair pair : labelledCases.cachePairs()) {
      if (pair.servable()) {
        continue;
      }
      negatives.add(Similarity.cosine(
          embeddings.computeIfAbsent(pair.query(), embedder::embed),
          embeddings.computeIfAbsent(pair.entry(), embedder::embed)));
    }

    if (negatives.isEmpty()) {
      throw new IllegalStateException(
          "The labelled set holds no non-servable cache pair, so there is nothing to "
              + "bound the wrong-answer rate with.");
    }

    double qhat = quantileOrFail(negatives, alpha, CalibrationTarget.CACHE);
    return new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, alpha, qhat, negatives.size(),
        embedder.modelId(), null, Instant.now());
  }

  /** The live routes, embedded once per calibration run. */
  private List<EmbeddedRoute> embedRoutes() {
    List<EmbeddedRoute> embedded = new ArrayList<>();
    for (SemanticRoute route : routingConfig.get().routes()) {
      if (route.tier() == null || route.examples().isEmpty()) {
        continue;
      }
      embedded.add(new EmbeddedRoute(route,
          route.examples().stream().map(embedder::embed).toList()));
    }
    if (embedded.isEmpty()) {
      throw new IllegalStateException(
          "No usable semantic route is configured, so there is nothing to calibrate "
              + "routing against.");
    }
    return embedded;
  }

  private static double quantileOrFail(List<Double> scores, double alpha,
                                       CalibrationTarget target) {
    return ConformalQuantile.of(scores, alpha).orElseThrow(() ->
        new IllegalStateException(
            "%d labelled cases cannot support alpha=%s for %s: the conformal quantile "
                .formatted(scores.size(), alpha, target)
                + "needs at least " + ConformalQuantile.minimumSampleSize(alpha)
                + ". Lower the confidence or label more cases."));
  }

  private double fixedFallback(CalibrationTarget target) {
    return switch (target) {
      case CACHE -> fixedCacheThreshold;
      case ROUTING -> routingConfig.get().routeSimilarityThreshold();
    };
  }

  /**
   * The stored calibrations, re-read at most once per {@link #SNAPSHOT_TTL}.
   *
   * <p>A store that is unreachable keeps the last snapshot rather than dropping
   * to "uncalibrated": losing the database should not silently change how the
   * gateway routes and caches.
   */
  private Map<CalibrationTarget, ConformalCalibration> current() {
    Snapshot cached = snapshot;
    if (cached != null && !cached.isOlderThan(SNAPSHOT_TTL)) {
      return cached.byTarget();
    }

    try {
      Map<CalibrationTarget, ConformalCalibration> loaded =
          new EnumMap<>(CalibrationTarget.class);
      store.findAll().forEach(calibration ->
          loaded.put(calibration.target(), calibration));
      Snapshot fresh = new Snapshot(loaded, Instant.now());
      snapshot = fresh;
      return fresh.byTarget();
    } catch (RuntimeException e) {
      LOG.warn("Could not read the calibration store ({}), keeping the last known state",
          e.toString());
      return cached == null ? Map.of() : cached.byTarget();
    }
  }

  /**
   * Version of the live routing rules, recomputed only when they change.
   *
   * <p>Same caching trick as the routing advisor's own tracker; that one is
   * package-private in another adapter, and a second cached hash is cheaper than
   * a shared abstraction across layers for it.
   */
  private String routingConfigVersion() {
    RoutingConfig config = routingConfig.get();
    ConfigVersion cached = configVersion;
    if (cached != null && cached.config().equals(config)) {
      return cached.version();
    }
    ConfigVersion fresh = new ConfigVersion(config, RoutingConfigVersion.of(config));
    configVersion = fresh;
    return fresh.version();
  }

  /** Logs a transition once, so a calibration going stale is not silent. */
  private void logStatusChange(CalibrationTarget target, CalibrationStatus status) {
    if (lastLoggedStatus.put(target, status) == status) {
      return;
    }
    if (status == CalibrationStatus.VALID) {
      LOG.info("{} calibration is in force", target);
    } else {
      LOG.warn("{} calibration is {} — falling back to the fixed threshold {}",
          target, status, fixedFallback(target));
    }
  }

  private record Snapshot(Map<CalibrationTarget, ConformalCalibration> byTarget,
                          Instant loadedAt) {

    boolean isOlderThan(Duration ttl) {
      return Duration.between(loadedAt, Instant.now()).compareTo(ttl) > 0;
    }
  }

  private record ConfigVersion(RoutingConfig config, String version) {
  }
}
