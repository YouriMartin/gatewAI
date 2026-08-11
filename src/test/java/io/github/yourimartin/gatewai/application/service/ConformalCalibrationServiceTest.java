package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.LabelledCase;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.out.CalibrationStore;
import io.github.yourimartin.gatewai.domain.port.out.LabelledCaseSource;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConformalCalibrationServiceTest {

  private static final double FIXED_CACHE_THRESHOLD = 0.92;
  private static final double FIXED_ROUTING_THRESHOLD = 0.60;

  private CalibrationStore store;
  private LabelledCaseSource labelledCases;
  private TextEmbedder embedder;
  private RoutingConfigPort routingConfig;

  @BeforeEach
  void setUp() {
    store = new InMemoryStore();
    labelledCases = mock(LabelledCaseSource.class);
    embedder = mock(TextEmbedder.class);
    routingConfig = mock(RoutingConfigPort.class);

    when(embedder.modelId()).thenReturn("nomic-embed-text");
    when(routingConfig.get()).thenReturn(new RoutingConfig("embedding", 100, 500,
        List.of(), FIXED_ROUTING_THRESHOLD,
        List.of(new SemanticRoute("code", ModelTier.CLOUD_PREMIUM, List.of("refactor")))));
    when(labelledCases.description()).thenReturn("test labels");
    when(labelledCases.routingCases()).thenReturn(List.of());
    when(labelledCases.cachePairs()).thenReturn(List.of());
  }

  @Test
  @DisplayName("with nothing calibrated, the fixed thresholds are what is in force")
  void degradesToFixedThresholdsWhenNothingIsCalibrated() {
    ConformalCalibrationService service = service(true);

    CalibrationState cache = service.state(CalibrationTarget.CACHE);
    CalibrationState routing = service.state(CalibrationTarget.ROUTING);

    assertEquals(CalibrationStatus.ABSENT, cache.status());
    assertFalse(cache.isApplied());
    assertEquals(FIXED_CACHE_THRESHOLD, cache.effectiveThreshold());
    assertEquals(FIXED_ROUTING_THRESHOLD, routing.effectiveThreshold());
  }

  @Test
  @DisplayName("routing scores come from the same scoring the router ranks with")
  void fitsRoutingOnTheScoresTheRouterDecidesWith() {
    when(labelledCases.routingCases()).thenReturn(List.of(
        new LabelledCase.Routing("a", ModelTier.CLOUD_PREMIUM),
        new LabelledCase.Routing("b", ModelTier.CLOUD_PREMIUM),
        new LabelledCase.Routing("c", ModelTier.CLOUD_PREMIUM)));
    stubCandidates("a", 0.80);
    stubCandidates("b", 0.70);
    stubCandidates("c", 0.50);
    when(labelledCases.cachePairs()).thenReturn(cachePairs(0.10, 0.20, 0.30));

    service(true).recalibrate(0.25, 0.25);

    ConformalCalibration routing =
        store.find(CalibrationTarget.ROUTING).orElseThrow();
    assertEquals(ConformalGuarantee.CORRECT_TARGET_COVERAGE, routing.guarantee());
    assertEquals(3, routing.sampleSize());
    // Non-conformity 1-score = {0.20, 0.30, 0.50}; ceil(4 * 0.75) = 3 -> 0.50.
    // Tolerance is float-sized: the scores come from float32 vectors, as in
    // production.
    assertEquals(0.50, routing.qhat(), 1e-6);
    assertEquals(0.50, routing.similarityThreshold(), 1e-6);
  }

  @Test
  @DisplayName("the cache is fitted on the pairs that must not be served")
  void fitsTheCacheOnTheNegativeClass() {
    when(labelledCases.cachePairs()).thenReturn(List.of(
        new LabelledCase.CachePair("q1", "e1", false),
        new LabelledCase.CachePair("q2", "e2", false),
        new LabelledCase.CachePair("q3", "e3", false),
        new LabelledCase.CachePair("servable", "entry", true)));
    // Orthogonal, identical and 45-degree pairs: similarities 0, 1 and ~0.707.
    when(embedder.embed("q1")).thenReturn(new float[] {1, 0});
    when(embedder.embed("e1")).thenReturn(new float[] {0, 1});
    when(embedder.embed("q2")).thenReturn(new float[] {1, 0});
    when(embedder.embed("e2")).thenReturn(new float[] {1, 1});
    when(embedder.embed("q3")).thenReturn(new float[] {1, 0});
    when(embedder.embed("e3")).thenReturn(new float[] {1, 0});
    when(labelledCases.routingCases()).thenReturn(List.of(
        new LabelledCase.Routing("a", ModelTier.CLOUD_PREMIUM)));
    stubCandidates("a", 0.80);

    service(true).recalibrate(0.50, 0.25);

    ConformalCalibration cache = store.find(CalibrationTarget.CACHE).orElseThrow();
    assertEquals(ConformalGuarantee.WRONG_ANSWER_RATE, cache.guarantee());
    assertEquals(3, cache.sampleSize(), "only the non-servable pairs are fitted on");
    // Similarities {0, 0.707, 1}; ceil(4 * 0.75) = 3 -> the largest, 1.0.
    assertEquals(1.0, cache.similarityThreshold(), 1e-6);
    assertEquals(null, cache.routingConfigVersion(),
        "a cache calibration does not depend on the routes");
  }

  @Test
  @DisplayName("a sample too small for the level asked for fails loudly")
  void refusesToPromiseWhatTheSampleCannotSupport() {
    when(labelledCases.routingCases()).thenReturn(List.of(
        new LabelledCase.Routing("a", ModelTier.CLOUD_PREMIUM)));
    stubCandidates("a", 0.80);

    IllegalStateException failure = assertThrows(IllegalStateException.class,
        () -> service(true).recalibrate(0.01, 0.01));

    assertTrue(failure.getMessage().contains("at least"),
        "the message should say how many cases would be needed: " + failure.getMessage());
    assertTrue(store.findAll().isEmpty(), "nothing is stored when the fit fails");
  }

  @Test
  @DisplayName("labels for a tier no route covers are a failure, not an empty fit")
  void refusesWhenNoRouteCoversTheLabelledTiers() {
    // The only configured route is CLOUD_PREMIUM, so a LOCAL label has no route
    // to be scored against and the sample is empty.
    when(labelledCases.routingCases()).thenReturn(List.of(
        new LabelledCase.Routing("a", ModelTier.LOCAL)));
    stubCandidates("a", 0.80);

    assertThrows(IllegalStateException.class, () -> service(true).recalibrate(0.10, 0.10));
  }

  @Test
  @DisplayName("a calibration fitted with another embedding model is not applied")
  void staleCalibrationDegradesToTheFixedThreshold() {
    store.save(new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94, 200,
        "some-other-model", null, java.time.Instant.now()));

    CalibrationState state = service(true).state(CalibrationTarget.CACHE);

    assertEquals(CalibrationStatus.STALE, state.status());
    assertFalse(state.isApplied());
    assertEquals(FIXED_CACHE_THRESHOLD, state.effectiveThreshold());
  }

  @Test
  @DisplayName("switching calibration off keeps the work but restores fixed thresholds")
  void disabledCalibrationIsReportedAsSuch() {
    store.save(new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94, 200,
        "nomic-embed-text", null, java.time.Instant.now()));

    CalibrationState state = service(false).state(CalibrationTarget.CACHE);

    assertEquals(CalibrationStatus.DISABLED, state.status());
    assertEquals(FIXED_CACHE_THRESHOLD, state.effectiveThreshold());
  }

  @Test
  @DisplayName("a store that is down degrades the threshold, never the request")
  void anUnreachableStoreNeverThrowsAtTheCaller() {
    CalibrationStore failing = mock(CalibrationStore.class);
    when(failing.findAll()).thenThrow(new IllegalStateException("database is down"));

    ConformalCalibrationService service = new ConformalCalibrationService(
        failing, labelledCases, embedder, routingConfig,
        true, 0.10, 0.10, FIXED_CACHE_THRESHOLD);

    // This runs inside the cache advisor and the router: it is on the request
    // path, so it must answer even when the database is gone.
    CalibrationState state = service.state(CalibrationTarget.CACHE);

    assertEquals(CalibrationStatus.ABSENT, state.status());
    assertEquals(FIXED_CACHE_THRESHOLD, state.effectiveThreshold());
  }

  private ConformalCalibrationService service(boolean enabled) {
    return new ConformalCalibrationService(store, labelledCases, embedder,
        routingConfig, enabled, 0.10, 0.10, FIXED_CACHE_THRESHOLD);
  }

  /**
   * Makes {@code prompt} score exactly {@code score} against the single "code"
   * route, by placing its vector at that cosine from the route example's.
   */
  private void stubCandidates(String prompt, double score) {
    when(embedder.embed("refactor")).thenReturn(new float[] {1, 0});
    when(embedder.embed(prompt)).thenReturn(
        new float[] {(float) score, (float) Math.sqrt(1 - score * score)});
  }

  private static List<LabelledCase.CachePair> cachePairs(double... similarities) {
    List<LabelledCase.CachePair> pairs = new ArrayList<>();
    for (int i = 0; i < similarities.length; i++) {
      pairs.add(new LabelledCase.CachePair("q" + i, "e" + i, false));
    }
    return pairs;
  }

  /** A store with no database behind it, so the fit is what is under test. */
  private static final class InMemoryStore implements CalibrationStore {

    private final List<ConformalCalibration> saved = new ArrayList<>();

    @Override
    public Optional<ConformalCalibration> find(CalibrationTarget target) {
      return saved.stream().filter(c -> c.target() == target).findFirst();
    }

    @Override
    public List<ConformalCalibration> findAll() {
      return List.copyOf(saved);
    }

    @Override
    public void save(ConformalCalibration calibration) {
      saved.removeIf(c -> c.target() == calibration.target());
      saved.add(calibration);
    }
  }
}
