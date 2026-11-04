package io.github.yourimartin.gatewai.domain.model.shadow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WouldHitAssessmentTest {

  @Test
  void aVerdictIsRecordedExactlyWhenTheWouldHitWasJudged() {
    assertThatThrownBy(() -> assessment(JudgeOutcome.JUDGED, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> assessment(JudgeOutcome.NOT_SAMPLED, JudgeVerdict.YES))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> assessment(JudgeOutcome.FAILED, JudgeVerdict.NO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static WouldHitAssessment assessment(JudgeOutcome outcome, JudgeVerdict verdict) {
    return new WouldHitAssessment(UUID.randomUUID(), "c", "client", Instant.now(), 0.9, false,
        "m", outcome, verdict, "judge", JudgeRubric.VERSION, null);
  }
}
