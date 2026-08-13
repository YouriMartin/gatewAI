package io.github.yourimartin.gatewai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.yourimartin.gatewai.CalibrationFixtures;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.RoutingConfigVersion;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.infrastructure.llm.EvalClassifierFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The evaluation task (v2 batch 5): scores the routing and cache decisions on
 * labelled data, writes a report comparable across commits, and fails the build
 * when a metric drops below its committed baseline.
 *
 * <p>Hermetic by design. The classifier under test is the production one, its
 * configuration is the shipped one, and the only thing replaced is the model
 * server — by vectors recorded once and committed. So this runs in the ordinary
 * {@code ./mvnw test}, with no Ollama, no database and no network, which is the
 * difference between a quality gate that runs on every commit and one that runs
 * when someone remembers.
 *
 * <p>What it therefore does <b>not</b> detect: a change in the embedding model
 * itself. That is a re-record, and the fixtures' provenance makes it explicit
 * rather than silent.
 */
class EvaluationHarnessTest {

  /** The plan's target for a calibration set batch 3 can fit a quantile on. */
  private static final int MINIMUM_CALIBRATION_SIZE = 200;

  /**
   * Margin bands the cascade is scored at. The shipped band must be one of
   * them, so the report always shows the default next to what it gives up.
   */
  private static final double[] BAND_SWEEP = {0.01, 0.02, 0.03, 0.05, 0.08};

  /** Standard errors of slack allowed around a conformal guarantee. */
  private static final double COVERAGE_TOLERANCE_SIGMA = 2.0;

  private static EvalConfig config;
  private static EvalBaselines baselines;
  private static List<RoutingSample> routingCalibration;
  private static List<RoutingSample> routingTest;
  private static List<CachePair> cacheCalibration;
  private static List<CachePair> cacheTest;

  private static RoutingEvaluator.Result routingCalibrationResult;
  private static RoutingEvaluator.Result routingTestResult;
  private static RoutingEvaluator.Result heuristicBaselineResult;
  private static RoutingEvaluator.Result routingTestCalibratedResult;
  private static RoutingEvaluator.Result cascadeTestResult;
  private static Map<Double, RoutingEvaluator.Result> cascadeBandSweep;
  private static CacheEvaluator.Result cacheCalibrationResult;
  private static CacheEvaluator.Result cacheTestResult;
  private static CacheEvaluator.Result cacheTestCalibratedResult;
  private static SavingsEstimator.Estimate savings;
  private static VectorFixture vectors;
  private static SimilarityFixture similarities;
  private static ConformalCalibration routingFit;
  private static ConformalCalibration cacheFit;
  private static HarnessCalibrator.Coverage routingCoverage;
  private static HarnessCalibrator.Coverage cacheWrongAnswerRate;

  @BeforeAll
  static void evaluate() {
    config = EvalConfig.load();
    baselines = EvalBaselines.load();
    RoutingConfig routingConfig = config.routingConfig();

    routingCalibration = EvalDatasets.routing(EvalDatasets.ROUTING_CALIBRATION);
    routingTest = EvalDatasets.routing(EvalDatasets.ROUTING_TEST);
    cacheCalibration = EvalDatasets.cache(EvalDatasets.CACHE_CALIBRATION);
    cacheTest = EvalDatasets.cache(EvalDatasets.CACHE_TEST);

    vectors = VectorFixture.load(EvalPaths.ROUTING_VECTORS);
    similarities = SimilarityFixture.load(EvalPaths.CACHE_SIMILARITIES);
    requireFullFixtureCoverage();

    ReplayEmbeddingModel replay =
        new ReplayEmbeddingModel(vectors.vectors(), vectors.provenance().dimensions());
    var embeddingClassifier = EvalClassifierFactory.embeddingClassifier(
        replay, routingConfig,
        CalibrationFixtures.none(routingConfig.routeSimilarityThreshold()));
    var heuristicClassifier = EvalClassifierFactory.heuristicClassifier(routingConfig);

    routingCalibrationResult = RoutingEvaluator.evaluate(
        "routing-calibration", "embedding", routingCalibration, embeddingClassifier);
    routingTestResult = RoutingEvaluator.evaluate(
        "routing-test", "embedding", routingTest, embeddingClassifier);
    heuristicBaselineResult = RoutingEvaluator.evaluate(
        "routing-test", "heuristic", routingTest, heuristicClassifier);

    double threshold = config.cacheSimilarityThreshold();
    cacheCalibrationResult = CacheEvaluator.evaluate(
        "cache-calibration", cacheCalibration, similarities.similarities(), threshold);
    cacheTestResult = CacheEvaluator.evaluate(
        "cache-test", cacheTest, similarities.similarities(), threshold);

    // --- v2 batch 3: fit on the calibration halves, measure on the test halves.
    String embeddingModel = vectors.provenance().embeddingModel();
    routingFit = HarnessCalibrator.routing(routingCalibration,
        embeddingClassifier, config.routingAlpha(), embeddingModel,
        RoutingConfigVersion.of(routingConfig));
    cacheFit = HarnessCalibrator.cache(cacheCalibration,
        similarities.similarities(), config.cacheAlpha(), embeddingModel);

    var calibratedClassifier = EvalClassifierFactory.embeddingClassifier(
        replay, routingConfig,
        CalibrationFixtures.applied(routingFit,
            routingConfig.routeSimilarityThreshold()));

    routingTestCalibratedResult = RoutingEvaluator.evaluate(
        "routing-test", "embedding+conformal", routingTest, calibratedClassifier);
    cacheTestCalibratedResult = CacheEvaluator.evaluate(
        "cache-test", cacheTest, similarities.similarities(),
        cacheFit.similarityThreshold());

    // --- v2 batch 4: the cascade, on the same calibrated threshold.
    cascadeBandSweep = new LinkedHashMap<>();
    for (double band : BAND_SWEEP) {
      cascadeBandSweep.put(band, RoutingEvaluator.evaluate(
          "routing-test", "cascade", routingTest,
          EvalClassifierFactory.cascadeClassifier(replay, routingConfig,
              CalibrationFixtures.applied(routingFit,
                  routingConfig.routeSimilarityThreshold()), band)));
    }
    cascadeTestResult = cascadeBandSweep.get(config.cascadeMarginBand());
    if (cascadeTestResult == null) {
      // The shipped band has to be one of the swept ones, or the report would
      // publish a trade-off curve that does not contain the default.
      throw new IllegalStateException("The configured cascade margin band ("
          + config.cascadeMarginBand() + ") is not in the sweep "
          + Arrays.toString(BAND_SWEEP));
    }

    routingCoverage = HarnessCalibrator.routingCoverage(
        routingTest, embeddingClassifier, routingFit);
    cacheWrongAnswerRate = HarnessCalibrator.cacheWrongAnswerRate(
        cacheTest, similarities.similarities(), cacheFit);

    savings = SavingsEstimator.estimate(
        routingTestCalibratedResult.predictions(), config);

    EvalReport report = new EvalReport(vectors.provenance(),
        RoutingConfigVersion.of(routingConfig));
    report.routing("routingCalibration", routingCalibrationResult);
    report.routing("routingTest", routingTestResult);
    report.routing("routingTestHeuristicBaseline", heuristicBaselineResult);
    report.routing("routingTestCalibrated", routingTestCalibratedResult);
    report.cache("cacheCalibration", cacheCalibrationResult);
    report.cache("cacheTest", cacheTestResult);
    report.cache("cacheTestCalibrated", cacheTestCalibratedResult);
    report.savings(savings, routingTestCalibratedResult);
    report.decisionLatency(vectors.decisionLatency());
    report.routing("routingTestCascade", cascadeTestResult);
    report.conformal(routingFit, routingCoverage, cacheFit, cacheWrongAnswerRate);
    report.escalation(cascadeTestResult, routingTestCalibratedResult,
        config.cascadeMarginBand(), cascadeBandSweep);
    report.write(EvalPaths.REPORT_DIR);

    System.out.printf(
        "Evaluation: routing %.1f%% fixed -> %.1f%% calibrated (heuristic %.1f%%), "
            + "cache FP %.1f%% -> %.1f%%, FN %.1f%% -> %.1f%%, "
            + "coverage %.1f%% (target %.1f%%) — report in %s%n",
        routingTestResult.accuracy() * 100,
        routingTestCalibratedResult.accuracy() * 100,
        heuristicBaselineResult.accuracy() * 100,
        cacheTestResult.confusion().falsePositiveRate() * 100,
        cacheTestCalibratedResult.confusion().falsePositiveRate() * 100,
        cacheTestResult.confusion().falseNegativeRate() * 100,
        cacheTestCalibratedResult.confusion().falseNegativeRate() * 100,
        routingCoverage.rate() * 100, routingCoverage.target() * 100,
        EvalPaths.REPORT_DIR.toAbsolutePath());
  }

  /**
   * Refuses to score at all unless every case has a recorded vector.
   *
   * <p>Not belt and braces: {@code EmbeddingComplexityClassifier} deliberately
   * survives an embedding failure by falling back to the heuristic, so a missing
   * vector does <b>not</b> surface as an error — it silently turns into a
   * degraded decision and a plausible-looking report. That robustness is right
   * in production and wrong here, so the gap is checked before anything is
   * measured rather than inferred from the numbers afterwards.
   */
  private static void requireFullFixtureCoverage() {
    Set<String> recorded = vectors.vectors().keySet();
    List<String> missing = Stream.concat(routingCalibration.stream(), routingTest.stream())
        .filter(sample -> !recorded.contains(sample.prompt()))
        .map(RoutingSample::id)
        .toList();
    List<String> unscored = Stream.concat(cacheCalibration.stream(), cacheTest.stream())
        .filter(pair -> !similarities.similarities().containsKey(pair.id()))
        .map(CachePair::id)
        .toList();

    if (!missing.isEmpty() || !unscored.isEmpty()) {
      throw new IllegalStateException(
          "Fixtures do not cover the datasets (routing: " + missing
              + ", cache: " + unscored + "). Re-record: " + EvalPaths.RECORD_COMMAND);
    }
  }

  @Test
  @DisplayName("calibration and test sets are disjoint and big enough to calibrate on")
  void datasetsAreDisjoint() {
    assertTrue(routingCalibration.size() >= MINIMUM_CALIBRATION_SIZE,
        "routing calibration set is too small to fit a conformal quantile on");
    assertTrue(cacheCalibration.size() >= MINIMUM_CALIBRATION_SIZE,
        "cache calibration set is too small to fit a conformal quantile on");

    Set<String> calibrationPrompts = routingCalibration.stream()
        .map(RoutingSample::prompt).collect(Collectors.toSet());
    assertTrue(routingTest.stream().noneMatch(s -> calibrationPrompts.contains(s.prompt())),
        "a prompt appears in both the routing calibration and test sets");

    Set<String> calibrationPairs = cacheCalibration.stream()
        .map(pair -> pair.query() + " " + pair.entry()).collect(Collectors.toSet());
    assertTrue(cacheTest.stream()
            .noneMatch(pair -> calibrationPairs.contains(pair.query() + " " + pair.entry())),
        "a pair appears in both the cache calibration and test sets");

    Set<String> ids = new HashSet<>();
    for (RoutingSample sample : routingCalibration) {
      assertTrue(ids.add(sample.id()), "duplicate id " + sample.id());
    }
    for (RoutingSample sample : routingTest) {
      assertTrue(ids.add(sample.id()), "duplicate id " + sample.id());
    }
  }

  @Test
  @DisplayName("no evaluation prompt is a route example in disguise")
  void datasetsDoNotLeakRouteExamples() {
    Set<String> examples = config.routingConfig().routes().stream()
        .map(SemanticRoute::examples)
        .flatMap(List::stream)
        .map(EvaluationHarnessTest::normalise)
        .collect(Collectors.toSet());

    for (RoutingSample sample : routingCalibration) {
      assertFalse(examples.contains(normalise(sample.prompt())),
          sample.id() + " is a copy of a route example: it would score 1.0 for free");
    }
    for (RoutingSample sample : routingTest) {
      assertFalse(examples.contains(normalise(sample.prompt())),
          sample.id() + " is a copy of a route example: it would score 1.0 for free");
    }
  }

  @Test
  @DisplayName("fixtures describe the datasets and routing rules in force")
  void fixturesAreCurrent() {
    assertEquals(
        EvalDatasets.digest(EvalDatasets.ROUTING_CALIBRATION, EvalDatasets.ROUTING_TEST),
        vectors.provenance().datasetDigest(),
        "routing fixtures were recorded on different data. Re-record: "
            + EvalPaths.RECORD_COMMAND);
    assertEquals(
        EvalDatasets.digest(EvalDatasets.CACHE_CALIBRATION, EvalDatasets.CACHE_TEST),
        similarities.provenance().datasetDigest(),
        "cache fixtures were recorded on different data. Re-record: "
            + EvalPaths.RECORD_COMMAND);
    assertEquals(RoutingConfigVersion.of(config.routingConfig()),
        vectors.provenance().routingConfigVersion(),
        "routing fixtures were recorded under different routing rules. Re-record: "
            + EvalPaths.RECORD_COMMAND);
    assertEquals(config.embeddingModelId(), vectors.provenance().embeddingModel(),
        "fixtures were recorded with a different embedding model. Re-record: "
            + EvalPaths.RECORD_COMMAND);
  }

  @Test
  @DisplayName("routing accuracy holds, on both sets and in both languages")
  void routingAccuracyMeetsBaseline() {
    assertMetric("routingAccuracyCalibrationMin", routingCalibrationResult.accuracy());
    assertMetric("routingAccuracyTestMin", routingTestResult.accuracy());
    assertMetric("routingAccuracyCalibratedTestMin",
        routingTestCalibratedResult.accuracy());
    assertMetric("routingAccuracyFrenchMin",
        routingTestResult.byLanguage().get("fr").accuracy());
    assertMetric("routingAccuracyEnglishMin",
        routingTestResult.byLanguage().get("en").accuracy());
  }

  @Test
  @DisplayName("semantic routing still beats the heuristic it falls back to")
  void embeddingStrategyBeatsTheHeuristicBaseline() {
    assertTrue(routingTestResult.accuracy() > heuristicBaselineResult.accuracy(),
        "the embedding strategy (%.3f) no longer beats the heuristic (%.3f): the extra "
            .formatted(routingTestResult.accuracy(), heuristicBaselineResult.accuracy())
            + "embedding call is not paying for itself");
  }

  @Test
  @DisplayName("the cache serves the wrong answer no more often than it used to")
  void cacheErrorsMeetBaseline() {
    assertAtMost("cacheFalsePositiveRateCalibrationMax",
        cacheCalibrationResult.confusion().falsePositiveRate());
    assertAtMost("cacheFalsePositiveRateTestMax",
        cacheTestResult.confusion().falsePositiveRate());
    assertAtMost("cacheFalseNegativeRateCalibrationMax",
        cacheCalibrationResult.confusion().falseNegativeRate());
    assertAtMost("cacheFalseNegativeRateTestMax",
        cacheTestResult.confusion().falseNegativeRate());
    assertAtMost("cacheFalsePositiveRateCalibratedTestMax",
        cacheTestCalibratedResult.confusion().falsePositiveRate());
  }

  @Test
  @DisplayName("routing still saves the carbon it claims to")
  void savingsMeetBaseline() {
    assertMetric("gramsCo2SavedRatioMin", savings.gramsCo2SavedRatio());
  }

  @Test
  @DisplayName("the cascade escalates to the model no more often than budgeted")
  void cascadeEscalationRateMeetsBaseline() {
    assertAtMost("cascadeEscalationRateMax", cascadeTestResult.escalationRate());
  }

  @Test
  @DisplayName("the cascade's worst case stays bounded")
  void cascadeWorstCaseLossMeetsBaseline() {
    // Level 3 is stubbed by the heuristic, so this is the case where escalating
    // buys nothing at all: 24% of traffic handed to a 34%-accurate classifier.
    // It costs accuracy, and that is the honest floor to hold — the cascade is
    // worth running only where the classifier model beats the heuristic on the
    // requests it is given, which no hermetic run can measure.
    assertAtMost("cascadeWorstCaseAccuracyLossMax",
        routingTestCalibratedResult.accuracy() - cascadeTestResult.accuracy());
  }

  @Test
  @DisplayName("escalating targets the errors: they concentrate in the escalated bucket")
  void escalatedRequestsAreDenserInErrorsThanTheRest() {
    double escalatedShare = cascadeTestResult.escalationRate();
    double errorShare = cascadeTestResult.errorCapture();

    assertTrue(errorShare > escalatedShare,
        ("escalation holds %.0f%% of traffic but only %.0f%% of the errors: the "
            + "gate is picking requests at random")
            .formatted(escalatedShare * 100, errorShare * 100));
  }

  @Test
  @DisplayName("the routing calibration covers the correct route as often as promised")
  void routingCoverageHoldsOnUnseenPrompts() {
    assertCoverageAtLeast(routingCoverage);
  }

  @Test
  @DisplayName("the cache calibration serves wrong answers no more often than promised")
  void cacheWrongAnswerRateHoldsOnUnseenPairs() {
    assertRateAtMost(cacheWrongAnswerRate);
  }

  @Test
  @DisplayName("calibrating beats the fixed thresholds it replaces")
  void calibrationImprovesOnTheFixedThresholds() {
    assertTrue(routingTestCalibratedResult.accuracy() >= routingTestResult.accuracy(),
        "calibrated routing (%.3f) is worse than the fixed 0.60 threshold (%.3f)"
            .formatted(routingTestCalibratedResult.accuracy(),
                routingTestResult.accuracy()));
    assertTrue(cacheTestCalibratedResult.confusion().falsePositiveRate()
            <= cacheTestResult.confusion().falsePositiveRate(),
        "the calibrated cache serves more wrong answers (%.3f) than the fixed 0.92 "
            .formatted(cacheTestCalibratedResult.confusion().falsePositiveRate())
            + "threshold (%.3f)".formatted(
                cacheTestResult.confusion().falsePositiveRate()));
  }

  /**
   * The guarantee is marginal and the test set is finite, so the empirical rate
   * is allowed to sit {@value #COVERAGE_TOLERANCE_SIGMA} standard errors below
   * the promise. Asserting equality would be asserting that a coin lands exactly
   * on its expectation.
   */
  private static void assertCoverageAtLeast(HarnessCalibrator.Coverage coverage) {
    double floor = coverage.target()
        - COVERAGE_TOLERANCE_SIGMA * coverage.standardError();
    assertTrue(coverage.rate() >= floor,
        "empirical coverage %.3f is below %.3f (target %.3f, n=%d, 1 s.e. %.3f)"
            .formatted(coverage.rate(), floor, coverage.target(),
                coverage.sampleSize(), coverage.standardError()));
  }

  private static void assertRateAtMost(HarnessCalibrator.Coverage rate) {
    double ceiling = rate.target() + COVERAGE_TOLERANCE_SIGMA * rate.standardError();
    assertTrue(rate.rate() <= ceiling,
        "empirical rate %.3f is above %.3f (target %.3f, n=%d, 1 s.e. %.3f)"
            .formatted(rate.rate(), ceiling, rate.target(),
                rate.sampleSize(), rate.standardError()));
  }

  private static void assertMetric(String baseline, double measured) {
    double floor = baselines.get(baseline);
    assertTrue(measured >= floor,
        "%s: measured %.4f, baseline %.4f".formatted(baseline, measured, floor));
  }

  private static void assertAtMost(String baseline, double measured) {
    double ceiling = baselines.get(baseline);
    assertTrue(measured <= ceiling,
        "%s: measured %.4f, baseline %.4f".formatted(baseline, measured, ceiling));
  }

  /** Case, accent and punctuation insensitive, so a near-copy is caught too. */
  private static String normalise(String text) {
    return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD)
        .replaceAll("[^a-z0-9]+", "");
  }
}
