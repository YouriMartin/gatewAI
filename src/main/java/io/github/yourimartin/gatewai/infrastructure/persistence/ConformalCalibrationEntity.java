package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ConformalGuarantee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The current calibration for one target (v2 batch 3). The target is the primary
 * key: recalibrating overwrites, because there is exactly one threshold in force
 * at a time and a decision that needs explaining carries its own copy.
 */
@Entity
@Table(name = "conformal_calibration")
class ConformalCalibrationEntity {

  @Id
  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private CalibrationTarget target;

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
  }

  ConformalCalibration toDomain() {
    return new ConformalCalibration(target, guarantee, alpha, qhat, sampleSize,
        embeddingModel, routingConfigVersion, calibratedAt);
  }
}
