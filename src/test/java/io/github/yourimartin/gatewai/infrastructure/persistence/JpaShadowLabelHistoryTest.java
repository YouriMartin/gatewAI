package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import io.github.yourimartin.gatewai.domain.model.calibration.ShadowLabel;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;

import org.junit.jupiter.api.Test;

class JpaShadowLabelHistoryTest {

  private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

  @Test
  void mapsARowWhateverTimestampAndNumericTypesTheDriverReturns() {
    ShadowLabel expected = new ShadowLabel("a", AT, JudgeOutcome.JUDGED, JudgeVerdict.NO,
        "judge", "would-hit-v1", 0.95, 0.92, "model");

    for (Object at : new Object[] {AT, AT.atOffset(ZoneOffset.ofHours(1)),
        Timestamp.from(AT)}) {
      assertEquals(expected, JpaShadowLabelHistory.toLabel(new Object[] {"a", at, "JUDGED",
          "NO", "judge", "would-hit-v1", BigDecimal.valueOf(0.95), 0.92, "model"}),
          "timestamp as " + at.getClass().getSimpleName());
    }
  }

  @Test
  void aFailedLabelWithoutItsDecisionMapsToNulls() {
    ShadowLabel label = JpaShadowLabelHistory.toLabel(new Object[] {"a",
        OffsetDateTime.of(2026, 11, 20, 10, 0, 0, 0, ZoneOffset.UTC), "FAILED", null,
        "judge", "would-hit-v1", null, null, null});

    assertEquals(JudgeOutcome.FAILED, label.judgeOutcome());
    assertEquals(null, label.verdict());
    assertFalse(label.hasDecision());
  }
}
