package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationSource;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.calibration.ShadowFit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The current calibration for one target and one scope (v2 batch 3; per client
 * since v4 D.3, ADR 0019). (target, client) is the primary key: recalibrating
 * overwrites, because there is exactly one threshold in force per scope at a time
 * and a decision that needs explaining carries its own copy.
 */
@Entity
@Table(name = "conformal_calibration")
@IdClass(ConformalCalibrationId.class)
class ConformalCalibrationEntity {

  @Id
  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private CalibrationTarget target;

  /** {@link ConformalCalibrationId#GLOBAL} for the global row. */
  @Id
  @Column(name = "client_id", length = 255)
  private String clientId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ConformalGuarantee guarantee;

  @Column(nullable = false)
  private double alpha;

  @Column(name = "q_hat", nullable = false)
  private double qhat;

  @Column(name = "sample_size", nullable = false)
  private int sampleSize;

  @Column(name = "embedding_model")
  private String embeddingModel;

  @Column(name = "routing_config_version", length = 64)
  private String routingConfigVersion;

  @Column(name = "calibrated_at", nullable = false)
  private Instant calibratedAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private CalibrationSource source;

  @Column(name = "judge_model")
  private String judgeModel;

  @Column(name = "rubric_version", length = 32)
  private String rubricVersion;

  @Column(name = "label_floor")
  private Double labelFloor;

  /** {@link FitSummaryJson}; null on rows fitted before {@code V16}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "fit_summary")
  private String fitSummary;

  protected ConformalCalibrationEntity() {
    // JPA requires a no-arg constructor
  }

  ConformalCalibrationEntity(ConformalCalibration calibration) {
    this.target = calibration.target();
    this.guarantee = calibration.guarantee();
    this.alpha = calibration.alpha();
    this.qhat = calibration.qhat();
    this.sampleSize = calibration.sampleSize();
    this.embeddingModel = calibration.embeddingModel();
    this.routingConfigVersion = calibration.routingConfigVersion();
    this.calibratedAt = calibration.calibratedAt();
    this.clientId = calibration.isGlobal()
        ? ConformalCalibrationId.GLOBAL : calibration.clientId();
    this.source = calibration.source();
    ShadowFit fit = calibration.shadowFit();
    if (fit != null) {
      this.judgeModel = fit.judgeModel();
      this.rubricVersion = fit.rubricVersion();
      this.labelFloor = fit.labelFloor();
    }
    this.fitSummary = FitSummaryJson.toJson(calibration.fitSummary());
  }

  ConformalCalibration toDomain() {
    ShadowFit fit = source == CalibrationSource.SHADOW
        ? new ShadowFit(judgeModel, rubricVersion, labelFloor) : null;
    return new ConformalCalibration(target, guarantee, alpha, qhat, sampleSize,
        embeddingModel, routingConfigVersion, calibratedAt,
        ConformalCalibrationId.GLOBAL.equals(clientId) ? null : clientId, source, fit,
        FitSummaryJson.fromJson(fitSummary));
  }
}
