package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.routing.ClassificationJustification.HeuristicRule;
import io.github.yourimartin.gatewai.domain.model.routing.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.routing.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

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
