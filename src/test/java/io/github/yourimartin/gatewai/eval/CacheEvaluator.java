package io.github.yourimartin.gatewai.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores the cache's accept rule against labelled {@code (query, entry)} pairs
 * (v2 batch 5).
 *
 * <p>The two errors are not symmetric, and the report keeps them apart for that
 * reason. A <b>false negative</b> costs one model call: the cache refused an
 * answer it held. A <b>false positive</b> returns another question's answer to
 * the user, silently and with full confidence. That asymmetry is what batch 3
 * turns into an asymmetric α; this evaluator is what gives it numbers to work
 * from.
 *
 * <p>The threshold sweep is deliberately part of the output. A single figure at
 * the configured 0.92 says whether today is fine; the sweep says what the
 * choice actually buys, and is the input a calibration reads.
 */
final class CacheEvaluator {

  private static final double SWEEP_FROM = 0.60;
  private static final double SWEEP_TO = 1.00;
  private static final double SWEEP_STEP = 0.01;

  private CacheEvaluator() {
  }

  static Result evaluate(String dataset, List<CachePair> pairs,
                         Map<String, Double> similarities, double threshold) {

    List<Point> sweep = new ArrayList<>();
    for (double candidate = SWEEP_FROM; candidate <= SWEEP_TO + 1e-9;
         candidate += SWEEP_STEP) {
      sweep.add(confusion(pairs, similarities, round(candidate)).asPoint());
    }

    Confusion current = confusion(pairs, similarities, threshold);
    Map<String, Score> byTag = new LinkedHashMap<>();
    for (CachePair pair : pairs) {
      boolean served = similarity(similarities, pair) >= threshold;
      for (String tag : pair.tags()) {
        byTag.merge(tag, Score.of(served == pair.servable()), Score::plus);
      }
    }

    return new Result(dataset, threshold, pairs.size(), current, byTag,
        List.copyOf(sweep), current.falsePositiveIds(), current.falseNegativeIds());
  }

  private static Confusion confusion(List<CachePair> pairs,
                                     Map<String, Double> similarities, double threshold) {
    int truePositives = 0;
    int trueNegatives = 0;
    List<String> falsePositives = new ArrayList<>();
    List<String> falseNegatives = new ArrayList<>();

    for (CachePair pair : pairs) {
      boolean served = similarity(similarities, pair) >= threshold;
      if (served && pair.servable()) {
        truePositives++;
      } else if (!served && !pair.servable()) {
        trueNegatives++;
      } else if (served) {
        falsePositives.add(pair.id());
      } else {
        falseNegatives.add(pair.id());
      }
    }
    return new Confusion(threshold, truePositives, trueNegatives,
        List.copyOf(falsePositives), List.copyOf(falseNegatives));
  }

  private static double similarity(Map<String, Double> similarities, CachePair pair) {
    Double score = similarities.get(pair.id());
    if (score == null) {
      throw new IllegalStateException(
          "No recorded similarity for pair " + pair.id() + " — the fixtures are stale. "
              + "Re-record: " + EvalPaths.RECORD_COMMAND);
    }
    return score;
  }

  private static double round(double value) {
    return Math.round(value * 1000.0) / 1000.0;
  }

  /** Counts at one threshold. */
  record Confusion(double threshold, int truePositives, int trueNegatives,
                   List<String> falsePositiveIds, List<String> falseNegativeIds) {

    int falsePositives() {
      return falsePositiveIds.size();
    }

    int falseNegatives() {
      return falseNegativeIds.size();
    }

    /** Served wrongly, over everything that should never have been served. */
    double falsePositiveRate() {
      int negatives = trueNegatives + falsePositives();
      return negatives == 0 ? 0 : (double) falsePositives() / negatives;
    }

    /** Refused wrongly, over everything that could have been served. */
    double falseNegativeRate() {
      int positives = truePositives + falseNegatives();
      return positives == 0 ? 0 : (double) falseNegatives() / positives;
    }

    double accuracy() {
      int total = truePositives + trueNegatives + falsePositives() + falseNegatives();
      return total == 0 ? 0 : (double) (truePositives + trueNegatives) / total;
    }

    /** Share of pairs served from cache: the hit rate this policy would produce. */
    double hitRate() {
      int total = truePositives + trueNegatives + falsePositives() + falseNegatives();
      return total == 0 ? 0 : (double) (truePositives + falsePositives()) / total;
    }

    Point asPoint() {
      return new Point(threshold, falsePositiveRate(), falseNegativeRate(), hitRate());
    }
  }

  /** One point of the threshold sweep. */
  record Point(double threshold, double falsePositiveRate, double falseNegativeRate,
               double hitRate) {
  }

  /** Correct decisions out of attempts, for one slice of the dataset. */
  record Score(int total, int correct) {

    static Score of(boolean correct) {
      return new Score(1, correct ? 1 : 0);
    }

    Score plus(Score other) {
      return new Score(total + other.total, correct + other.correct);
    }

    double accuracy() {
      return total == 0 ? 0 : (double) correct / total;
    }
  }

  /**
   * What the configured threshold scored, plus the curve around it.
   *
   * @param dataset           which labelled set was scored
   * @param threshold         the similarity threshold in force
   * @param total             pairs scored
   * @param confusion         counts and rates at {@code threshold}
   * @param byTag             accuracy per tag: which kind of near-miss survives
   * @param sweep             false-positive and false-negative rates across
   *                          thresholds — batch 3's input
   * @param falsePositiveIds  pairs wrongly served, named
   * @param falseNegativeIds  pairs wrongly refused, named
   */
  record Result(String dataset, double threshold, int total, Confusion confusion,
                Map<String, Score> byTag, List<Point> sweep,
                List<String> falsePositiveIds, List<String> falseNegativeIds) {
  }
}
