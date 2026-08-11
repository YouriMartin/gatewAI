package io.github.yourimartin.gatewai.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.ConformalQuantile;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

/**
 * Fits calibrations from the recorded fixtures, and measures what they deliver
 * on the disjoint test sets (v2 batch 3).
 *
 * <p>The quantile itself is the production {@link ConformalQuantile}, and the
 * routing scores come from the production classifier's own justification — the
 * same two things {@code ConformalCalibrationService} uses. What is not shared
 * is the service's plumbing (reading labelled files, embedding pairs, storing
 * the result), which has its own unit test; replaying that here would mean
 * recording a vector for every cache text, tripling the fixtures to re-verify
 * a cosine.
 *
 * <p>What this class exists for is the acceptance criterion: a guarantee stated
 * at α is only worth anything if the empirical rate on data the calibration
 * never saw actually lands near it.
 */
final class HarnessCalibrator {

  private HarnessCalibrator() {
  }

  /**
   * Fits the routing threshold: non-conformity is {@code 1 − similarity} to the
   * closest example of a route mapped to the labelled tier.
   */
  static ConformalCalibration routing(List<RoutingSample> samples,
                                      ComplexityClassifier classifier,
                                      double alpha, String embeddingModel,
                                      String routingConfigVersion) {
    List<Double> scores = new ArrayList<>();
    for (RoutingSample sample : samples) {
      expectedTierScore(sample, classifier)
          .ifPresent(score -> scores.add(1 - score));
    }

    double qhat = ConformalQuantile.of(scores, alpha).orElseThrow(() ->
        new IllegalStateException("Too few usable routing cases for alpha=" + alpha));
    return new ConformalCalibration(CalibrationTarget.ROUTING,
        ConformalGuarantee.CORRECT_TARGET_COVERAGE, alpha, qhat, scores.size(),
        embeddingModel, routingConfigVersion, Instant.now());
  }

  /**
   * Fits the cache threshold on the pairs a human judged <b>not</b> servable, so
   * α bounds how often another question's answer is returned.
   */
  static ConformalCalibration cache(List<CachePair> pairs,
                                    Map<String, Double> similarities,
                                    double alpha, String embeddingModel) {
    List<Double> negatives = pairs.stream()
        .filter(pair -> !pair.servable())
        .map(pair -> similarity(similarities, pair))
        .toList();

    double qhat = ConformalQuantile.of(negatives, alpha).orElseThrow(() ->
        new IllegalStateException("Too few non-servable pairs for alpha=" + alpha));
    return new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, alpha, qhat, negatives.size(),
        embeddingModel, null, Instant.now());
  }

  /**
   * Share of prompts whose correct route is inside the prediction set — the
   * quantity {@code 1 − α} promises, measured on data the fit never saw.
   */
  static Coverage routingCoverage(List<RoutingSample> samples,
                                  ComplexityClassifier classifier,
                                  ConformalCalibration calibration) {
    int covered = 0;
    int measurable = 0;
    for (RoutingSample sample : samples) {
      OptionalDouble score = expectedTierScore(sample, classifier);
      if (score.isEmpty()) {
        continue;
      }
      measurable++;
      if (calibration.admits(score.getAsDouble())) {
        covered++;
      }
    }
    return new Coverage(measurable, covered, 1 - calibration.alpha());
  }

  /**
   * Share of non-servable pairs the calibration would serve anyway — the
   * quantity α bounds, measured on the disjoint test pairs.
   */
  static Coverage cacheWrongAnswerRate(List<CachePair> pairs,
                                       Map<String, Double> similarities,
                                       ConformalCalibration calibration) {
    int negatives = 0;
    int served = 0;
    for (CachePair pair : pairs) {
      if (pair.servable()) {
        continue;
      }
      negatives++;
      if (calibration.admits(similarity(similarities, pair))) {
        served++;
      }
    }
    return new Coverage(negatives, served, calibration.alpha());
  }

  /** The classifier's own similarity for the route that should have won. */
  private static OptionalDouble expectedTierScore(RoutingSample sample,
                                                  ComplexityClassifier classifier) {
    ClassificationOutcome outcome = classifier.classify(sample.prompt());
    return candidates(outcome.justification()).stream()
        .filter(candidate -> candidate.tier() == sample.expectedTier())
        .mapToDouble(ClassificationJustification.RouteCandidate::score)
        .max();
  }

  private static List<ClassificationJustification.RouteCandidate> candidates(
      ClassificationJustification justification) {
    return switch (justification) {
      case ClassificationJustification.Embedding embedding -> embedding.candidates();
      case ClassificationJustification.Fallback fallback ->
          fallback.evidence() instanceof ClassificationJustification.Embedding evidence
              ? evidence.candidates() : List.of();
      default -> List.of();
    };
  }

  private static double similarity(Map<String, Double> similarities, CachePair pair) {
    Double score = similarities.get(pair.id());
    if (score == null) {
      throw new IllegalStateException("No recorded similarity for " + pair.id());
    }
    return score;
  }

  /**
   * An empirical rate against the level it was promised at.
   *
   * @param sampleSize cases measured
   * @param hits       cases that fell the way the guarantee counts
   * @param target     the promised level: {@code 1 − α} for coverage, {@code α}
   *                   for an error rate
   */
  record Coverage(int sampleSize, int hits, double target) {

    double rate() {
      return sampleSize == 0 ? 0 : (double) hits / sampleSize;
    }

    /**
     * One standard error of a binomial proportion at the target level. A
     * finite test set cannot land exactly on the promise, and this is the scale
     * of the wobble it is allowed — quoting the guarantee without it would be
     * claiming a precision the sample does not have.
     */
    double standardError() {
      return sampleSize == 0 ? 0 : Math.sqrt(target * (1 - target) / sampleSize);
    }
  }
}
