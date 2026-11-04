package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;
import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitAssessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One shadow would-hit, compared with the answer served (v4 D.2). No text. */
@Entity
@Table(name = "would_hit_assessment")
class WouldHitAssessmentEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(name = "correlation_id", updatable = false, length = 64)
  private String correlationId;

  @Column(name = "client_id", updatable = false)
  private String clientId;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "answer_similarity", updatable = false)
  private Double answerSimilarity;

  @Column(name = "beyond_window", updatable = false, nullable = false)
  private boolean beyondWindow;

  @Column(name = "embedding_model", updatable = false)
  private String embeddingModel;

  @Enumerated(EnumType.STRING)
  @Column(name = "judge_outcome", updatable = false, nullable = false, length = 16)
  private JudgeOutcome judgeOutcome;

  @Enumerated(EnumType.STRING)
  @Column(name = "judge_verdict", updatable = false, length = 16)
  private JudgeVerdict judgeVerdict;

  @Column(name = "judge_model_id", updatable = false)
  private String judgeModelId;

  @Column(name = "rubric_version", updatable = false, length = 32)
  private String rubricVersion;

  @Column(name = "judge_correlation_id", updatable = false, length = 64)
  private String judgeCorrelationId;

  protected WouldHitAssessmentEntity() {
    // JPA requires a no-arg constructor
  }

  WouldHitAssessmentEntity(WouldHitAssessment assessment) {
    this.id = assessment.id();
    this.correlationId = assessment.correlationId();
    this.clientId = assessment.clientId();
    this.createdAt = assessment.createdAt();
    this.answerSimilarity = assessment.answerSimilarity();
    this.beyondWindow = assessment.beyondWindow();
    this.embeddingModel = assessment.embeddingModel();
    this.judgeOutcome = assessment.judgeOutcome();
    this.judgeVerdict = assessment.verdict();
    this.judgeModelId = assessment.judgeModel();
    this.rubricVersion = assessment.rubricVersion();
    this.judgeCorrelationId = assessment.judgeCorrelationId();
  }

  WouldHitAssessment toDomain() {
    return new WouldHitAssessment(id, correlationId, clientId, createdAt, answerSimilarity,
        beyondWindow, embeddingModel, judgeOutcome, judgeVerdict, judgeModelId, rubricVersion,
        judgeCorrelationId);
  }
}
