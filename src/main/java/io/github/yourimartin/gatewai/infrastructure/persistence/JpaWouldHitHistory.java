package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitCounts;
import io.github.yourimartin.gatewai.domain.port.out.WouldHitHistory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads the would-hit assessments back, aggregated in PostgreSQL (v4 D.2). */
@Component
class JpaWouldHitHistory implements WouldHitHistory {

  private final SpringDataWouldHitAssessmentRepository repository;

  JpaWouldHitHistory(SpringDataWouldHitAssessmentRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<WouldHitCounts> counts(Instant from, Instant to) {
    return repository.countsBetween(from, to).stream().map(JpaWouldHitHistory::toCounts)
        .toList();
  }

  static WouldHitCounts toCounts(Object[] row) {
    return new WouldHitCounts((String) row[0], (String) row[1], (String) row[2],
        count(row[3]), row[4] == null ? null : ((Number) row[4]).doubleValue(),
        count(row[5]), count(row[6]), count(row[7]), count(row[8]), count(row[9]));
  }

  private static long count(Object value) {
    return value == null ? 0 : ((Number) value).longValue();
  }
}
