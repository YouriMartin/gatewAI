package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitCounts;

import org.junit.jupiter.api.Test;

class JpaWouldHitHistoryTest {

  @Test
  void mapsTheAggregatedRowsWhateverNumericTypesTheDriverReturns() {
    SpringDataWouldHitAssessmentRepository repository =
        mock(SpringDataWouldHitAssessmentRepository.class);
    Instant from = Instant.parse("2026-11-01T00:00:00Z");
    Instant to = Instant.parse("2026-11-08T00:00:00Z");
    when(repository.countsBetween(from, to)).thenReturn(List.of(
        new Object[] {"c1", null, null, 12L, 0.93, 1L, 0L, 0L, 0L, 0L},
        new Object[] {"c1", "judge", "would-hit-v1", BigDecimal.valueOf(5), null,
            0, 3L, 1L, 1L, 0L}));

    List<WouldHitCounts> counts = new JpaWouldHitHistory(repository).counts(from, to);

    assertThat(counts).containsExactly(
        new WouldHitCounts("c1", null, null, 12, 0.93, 1, 0, 0, 0, 0),
        new WouldHitCounts("c1", "judge", "would-hit-v1", 5, null, 0, 3, 1, 1, 0));
  }
}
