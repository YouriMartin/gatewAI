package io.github.yourimartin.gatewai.domain.model.shadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class WrongHitEstimateTest {

  @Test
  void theRateIsNoOverYesPlusNoWithUnsureExcludedAndCounted() {
    WrongHitEstimate estimate = WrongHitEstimate.of(
        new WouldHitCounts("c1", "judge-model", "would-hit-v1", 20, 0.91, 1, 8, 2, 7, 3));

    assertThat(estimate.judged()).isEqualTo(10);
    assertThat(estimate.rate()).isCloseTo(0.2, within(1e-9));
    assertThat(estimate.interval()).isEqualTo(WilsonInterval.of(2, 10));
    assertThat(estimate.counts().unsure()).isEqualTo(7);
    assertThat(estimate.label())
        .isEqualTo("judged by judge-model (rubric would-hit-v1), not by a human");
  }

  @Test
  void withoutAVerdictThereIsNoRateAndTheLabelSaysWhy() {
    WrongHitEstimate unsureOnly = WrongHitEstimate.of(
        new WouldHitCounts("c1", "judge-model", "would-hit-v1", 3, 0.9, 0, 0, 0, 3, 0));
    WrongHitEstimate notJudged = WrongHitEstimate.of(
        new WouldHitCounts("c1", null, null, 40, 0.88, 2, 0, 0, 0, 0));

    assertThat(unsureOnly.rate()).isNull();
    assertThat(unsureOnly.interval()).isNull();
    assertThat(unsureOnly.label()).contains("not by a human").contains("no YES/NO verdict yet");
    assertThat(notJudged.rate()).isNull();
    assertThat(notJudged.label()).isEqualTo("not judged: answer similarity only");
  }
}
