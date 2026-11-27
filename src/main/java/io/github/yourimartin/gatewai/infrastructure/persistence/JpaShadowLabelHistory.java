package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.calibration.ShadowLabel;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;
import io.github.yourimartin.gatewai.domain.port.out.ShadowLabelHistory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link ShadowLabelHistory} (v4 D.3, ADR 0019). */
@Component
class JpaShadowLabelHistory implements ShadowLabelHistory {

  private final SpringDataWouldHitAssessmentRepository repository;

  JpaShadowLabelHistory(SpringDataWouldHitAssessmentRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ShadowLabel> labels(String clientId) {
    return repository.shadowLabels(clientId).stream()
        .map(JpaShadowLabelHistory::toLabel)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public long notSampled(String clientId) {
    return repository.countNotSampled(clientId);
  }

  @Override
  @Transactional(readOnly = true)
  public long ambiguousBecomingServable(String clientId, String embeddingModel,
                                        double floor, double qhat) {
    return repository.countAmbiguousBecomingServable(clientId, embeddingModel, floor, qhat);
  }

  /** One row of {@code shadowLabels}, whatever types the driver returns. */
  static ShadowLabel toLabel(Object[] row) {
    return new ShadowLabel((String) row[0], instant(row[1]),
        JudgeOutcome.valueOf((String) row[2]),
        row[3] == null ? null : JudgeVerdict.valueOf((String) row[3]),
        (String) row[4], (String) row[5], number(row[6]), number(row[7]),
        (String) row[8]);
  }

  private static Double number(Object value) {
    return value == null ? null : ((Number) value).doubleValue();
  }

  private static Instant instant(Object value) {
    return switch (value) {
      case null -> null;
      case Instant instant -> instant;
      case OffsetDateTime offset -> offset.toInstant();
      case Timestamp timestamp -> timestamp.toInstant();
      default -> throw new IllegalStateException(
          "unexpected timestamp type " + value.getClass().getName());
    };
  }
}
