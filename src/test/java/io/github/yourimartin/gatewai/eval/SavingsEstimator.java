package io.github.yourimartin.gatewai.eval;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CarbonCalculator;
import io.github.yourimartin.gatewai.domain.model.CarbonFootprint;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

/**
 * What the routing decisions would have saved against an all-premium baseline
 * (v2 batch 5), through the accounting the gateway already uses.
 *
 * <p>{@link CarbonCalculator} and the registry coefficients are the production
 * ones, so this figure moves when they move — no second, divergent cost model.
 *
 * <p>Two assumptions are stated rather than hidden, because they are the whole
 * uncertainty of the number:
 *
 * <ul>
 *   <li>prompt tokens are approximated as {@code characters / 4}, the usual rule
 *       of thumb for a mixed EN/FR corpus;</li>
 *   <li>every request is credited the same completion length, whichever tier
 *       serves it. Holding it constant is what isolates the effect of the
 *       routing decision; a premium model that answers at greater length would
 *       make the real saving larger, not smaller.</li>
 * </ul>
 *
 * <p>With the local-first default registry every tier costs zero euros, so the
 * euro saving is legitimately zero. Since v3 lot C.1 the same is true of carbon:
 * local models are {@link EnergySource#NOT_ACCOUNTED}, so a saving against them
 * is <b>not determinable</b> rather than zero or complete. The estimate says
 * which of the two it is ({@link Estimate#carbonAccounted()}) instead of
 * publishing a ratio computed from unaccounted models — a mixed registry would
 * otherwise report a 100 % carbon saving simply because the cheap tier is
 * unmetered. Point the registry at a cloud tier and both columns fill in.
 */
final class SavingsEstimator {

  /** Characters per token, the usual approximation for mixed EN/FR text. */
  private static final double CHARS_PER_TOKEN = 4.0;

  /** Completion tokens credited to every request, whichever tier serves it. */
  private static final int ASSUMED_COMPLETION_TOKENS = 400;

  private static final CarbonCalculator CALCULATOR = new CarbonCalculator();

  private SavingsEstimator() {
  }

  static Estimate estimate(List<RoutingEvaluator.Prediction> predictions, EvalConfig config) {
    ModelDefinition baselineModel = config.modelFor(ModelTier.CLOUD_PREMIUM);
    double gridIntensity = config.gridIntensityGramsPerKwh();

    long totalTokens = 0;
    double routedCost = 0;
    double baselineCost = 0;
    double routedGrams = 0;
    double baselineGrams = 0;
    int unaccountedRequests = 0;

    for (RoutingEvaluator.Prediction prediction : predictions) {
      long tokens = tokensFor(prediction.promptChars());
      ModelDefinition routedModel = config.modelFor(prediction.tier());

      totalTokens += tokens;
      routedCost += cost(routedModel, tokens);
      baselineCost += cost(baselineModel, tokens);
      routedGrams += footprint(routedModel, tokens, gridIntensity).gramsCo2();
      baselineGrams += footprint(baselineModel, tokens, gridIntensity).gramsCo2();
      if (!routedModel.energySource().accounted()) {
        unaccountedRequests++;
      }
    }

    return new Estimate(predictions.size(), totalTokens, ASSUMED_COMPLETION_TOKENS,
        gridIntensity, baselineModel.modelId(), baselineModel.energySource(),
        routedCost, baselineCost, routedGrams, baselineGrams, unaccountedRequests);
  }

  private static long tokensFor(int promptChars) {
    return Math.round(promptChars / CHARS_PER_TOKEN) + ASSUMED_COMPLETION_TOKENS;
  }

  private static double cost(ModelDefinition model, long tokens) {
    return tokens / 1000.0 * model.costPer1kTokens();
  }

  private static CarbonFootprint footprint(ModelDefinition model, long tokens,
                                           double gridIntensity) {
    return CALCULATOR.estimate(model, tokens, gridIntensity);
  }

  /**
   * The comparison, in the units the gateway already reports.
   *
   * @param requests                 how many requests were priced
   * @param totalTokens              total tokens attributed to them
   * @param assumedCompletionTokens  the stated assumption above
   * @param gridIntensityGramsPerKwh grid intensity used, gCO2 per kWh
   * @param baselineModelId          the premium model everything is compared to
   * @param baselineEnergySource     whether that baseline's energy is accounted
   * @param routedCost               cost of the routed mix
   * @param baselineCost             cost had everything gone to the premium tier
   * @param routedGramsCo2           carbon of the routed mix
   * @param baselineGramsCo2         carbon had everything gone premium
   * @param unaccountedRequests      requests routed to a model excluded from scope
   */
  record Estimate(int requests, long totalTokens, int assumedCompletionTokens,
                  double gridIntensityGramsPerKwh, String baselineModelId,
                  EnergySource baselineEnergySource,
                  double routedCost, double baselineCost,
                  double routedGramsCo2, double baselineGramsCo2,
                  int unaccountedRequests) {

    double costSaved() {
      return baselineCost - routedCost;
    }

    double gramsCo2Saved() {
      return baselineGramsCo2 - routedGramsCo2;
    }

    double costSavedRatio() {
      return baselineCost == 0 ? 0 : costSaved() / baselineCost;
    }

    double gramsCo2SavedRatio() {
      return baselineGramsCo2 == 0 ? 0 : gramsCo2Saved() / baselineGramsCo2;
    }

    /**
     * Whether a carbon saving can be claimed at all: the baseline and every
     * routed model must have an energy coefficient. Otherwise the ratio compares
     * a number against a blank and means nothing (v3 lot C.1).
     */
    boolean carbonAccounted() {
      return baselineEnergySource.accounted() && unaccountedRequests == 0;
    }
  }
}
