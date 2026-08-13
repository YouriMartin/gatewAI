package io.github.yourimartin.gatewai.eval;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;

/**
 * Scores a classifier against a labelled routing set (v2 batch 5).
 *
 * <p>Accuracy alone would hide the thing that matters most to a gateway whose
 * point is to spend less: <b>which way</b> it is wrong. Sending a greeting to
 * the premium tier wastes money and carbon; sending an architecture review to
 * the smallest local model returns a bad answer. Both are one point of accuracy
 * and they are not the same mistake, so they are counted separately.
 */
final class RoutingEvaluator {

  private RoutingEvaluator() {
  }

  static Result evaluate(String dataset, String strategy, List<RoutingSample> samples,
                         ComplexityClassifier classifier) {

    Map<ModelTier, Map<ModelTier, Integer>> confusion = new EnumMap<>(ModelTier.class);
    Map<ModelTier, Integer> predicted = new EnumMap<>(ModelTier.class);
    Map<String, Score> byTag = new LinkedHashMap<>();
    Map<String, Score> byLanguage = new LinkedHashMap<>();
    Map<String, Integer> effectiveStrategies = new LinkedHashMap<>();
    Map<String, Integer> fallbackCauses = new LinkedHashMap<>();
    Map<String, Integer> cascadeLevels = new LinkedHashMap<>();
    List<Miss> misses = new ArrayList<>();
    List<Prediction> predictions = new ArrayList<>();
    List<Double> margins = new ArrayList<>();

    int correct = 0;
    int overRouted = 0;
    int underRouted = 0;
    int escalated = 0;
    int errorsEscalated = 0;

    for (RoutingSample sample : samples) {
      ClassificationOutcome outcome = classifier.classify(sample.prompt());
      ModelTier actual = outcome.tier();
      boolean hit = actual == sample.expectedTier();

      confusion.computeIfAbsent(sample.expectedTier(), tier -> new EnumMap<>(ModelTier.class))
          .merge(actual, 1, Integer::sum);
      predicted.merge(actual, 1, Integer::sum);
      predictions.add(new Prediction(sample.id(), sample.prompt().length(), actual));
      byLanguage.merge(sample.language(), Score.of(hit), Score::plus);
      for (String tag : sample.tags()) {
        byTag.merge(tag, Score.of(hit), Score::plus);
      }

      ClassificationJustification justification = outcome.justification();
      if (justification instanceof ClassificationJustification.Cascade cascade) {
        cascadeLevels.merge(cascade.level().name(), 1, Integer::sum);
        if (cascade.level() == CascadeLevel.LLM) {
          escalated++;
          if (!hit) {
            errorsEscalated++;
          }
        }
      }
      effectiveStrategies.merge(justification.strategy().name(), 1, Integer::sum);
      if (justification instanceof ClassificationJustification.Fallback fallback) {
        fallbackCauses.merge(fallback.cause().name(), 1, Integer::sum);
      }
      embeddingEvidence(justification)
          .ifPresent(embedding -> margins.add(embedding.margin()));

      if (hit) {
        correct++;
      } else {
        misses.add(new Miss(sample.id(), sample.expectedTier(), actual, sample.tags()));
        if (actual.ordinal() > sample.expectedTier().ordinal()) {
          overRouted++;
        } else {
          underRouted++;
        }
      }
    }

    return new Result(dataset, strategy, samples.size(), correct, overRouted, underRouted,
        confusion, predicted, byTag, byLanguage, effectiveStrategies, fallbackCauses,
        cascadeLevels, escalated, errorsEscalated,
        mean(margins), List.copyOf(misses), List.copyOf(predictions));
  }

  /**
   * The route scores behind a decision, whether the embedding strategy decided or
   * handed over below threshold — a hand-over carries them as
   * {@link ClassificationJustification.Fallback#evidence()}, which is the case
   * batch 3 needs to see.
   */
  private static Optional<ClassificationJustification.Embedding> embeddingEvidence(
      ClassificationJustification justification) {
    return switch (justification) {
      case ClassificationJustification.Embedding embedding -> Optional.of(embedding);
      case ClassificationJustification.Fallback fallback ->
          fallback.evidence() instanceof ClassificationJustification.Embedding embedding
              ? Optional.of(embedding) : Optional.empty();
      case ClassificationJustification.Cascade cascade -> {
        Optional<ClassificationJustification.Embedding> escalatedOn =
            cascade.escalatedOn() == null
                ? Optional.empty() : embeddingEvidence(cascade.escalatedOn());
        yield escalatedOn.isPresent()
            ? escalatedOn : embeddingEvidence(cascade.decided());
      }
      default -> Optional.empty();
    };
  }

  private static double mean(List<Double> values) {
    return values.isEmpty() ? 0
        : values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
  }

  /**
   * What one classifier scored on one dataset.
   *
   * @param dataset             which labelled set was scored
   * @param strategy            which classifier produced these numbers
   * @param total               cases scored
   * @param correct             cases whose tier matched the label
   * @param overRouted          wrong, and more expensive than the label: money
   *                            and carbon spent for nothing
   * @param underRouted         wrong, and cheaper than the label: an answer the
   *                            chosen tier probably could not give
   * @param confusion           expected tier to actual tier to count
   * @param predictedTiers      how many requests each tier received
   * @param byTag               accuracy per dataset tag, which is where the
   *                            adversarial cases show up
   * @param byLanguage          accuracy per language: the known FR risk, measured
   * @param effectiveStrategies which strategy actually decided, and how often
   * @param fallbackCauses      why the configured strategy stepped aside
   * @param cascadeLevels       how far the cascade went, and how often — empty
   *                            for every other strategy
   * @param escalated           cases that reached the classifier model: the cost
   *                            of the cascade, stated in requests
   * @param errorsEscalated     misrouted cases <b>inside</b> that bucket. The
   *                            cascade can only fix what it escalates, so this
   *                            over the total number of errors is the ceiling on
   *                            what escalating can buy
   * @param meanMargin          mean {@code top1 - top2} over decisions that had
   *                            route scores; batch 3 calibrates on this
   * @param misses              every wrong case, named, so a regression is
   *                            diagnosable from the report alone
   * @param predictions         where each case was actually sent, which is what
   *                            the savings estimate is computed from
   */
  record Result(String dataset, String strategy, int total, int correct,
                int overRouted, int underRouted,
                Map<ModelTier, Map<ModelTier, Integer>> confusion,
                Map<ModelTier, Integer> predictedTiers,
                Map<String, Score> byTag, Map<String, Score> byLanguage,
                Map<String, Integer> effectiveStrategies,
                Map<String, Integer> fallbackCauses,
                Map<String, Integer> cascadeLevels,
                int escalated, int errorsEscalated,
                double meanMargin, List<Miss> misses, List<Prediction> predictions) {

    double accuracy() {
      return total == 0 ? 0 : (double) correct / total;
    }

    /** Share of requests that reached the classifier model. */
    double escalationRate() {
      return total == 0 ? 0 : (double) escalated / total;
    }

    /** Share of this run's errors that escalating had a chance to fix. */
    double errorCapture() {
      int errors = total - correct;
      return errors == 0 ? 0 : (double) errorsEscalated / errors;
    }
  }

  /** Hits out of attempts, for one slice of the dataset. */
  record Score(int total, int correct) {

    static Score of(boolean hit) {
      return new Score(1, hit ? 1 : 0);
    }

    Score plus(Score other) {
      return new Score(total + other.total, correct + other.correct);
    }

    double accuracy() {
      return total == 0 ? 0 : (double) correct / total;
    }
  }

  /** One misrouted case. */
  record Miss(String id, ModelTier expected, ModelTier actual, List<String> tags) {
  }

  /** Where one case was routed, and how long its prompt was. */
  record Prediction(String id, int promptChars, ModelTier tier) {
  }
}
