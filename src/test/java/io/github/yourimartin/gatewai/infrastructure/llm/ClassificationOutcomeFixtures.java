package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

/**
 * Test fixtures for the classifier port's return type. Used where a test cares
 * about the tier only and the justification is just required to be present.
 */
final class ClassificationOutcomeFixtures {

  private ClassificationOutcomeFixtures() {
  }

  static ClassificationOutcome outcome(ModelTier tier) {
    return new ClassificationOutcome(tier,
        ClassificationJustification.Heuristic.of(HeuristicRule.DEFAULT));
  }
}
