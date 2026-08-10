package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "cache_decision")
class CacheDecisionEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(name = "correlation_id", updatable = false, length = 64)
  private String correlationId;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "prompt_hash", updatable = false, nullable = false, length = 64)
  private String promptHash;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 16)
  private CacheOutcome outcome;

  @Column(name = "similarity_score", updatable = false)
  private double similarityScore;

  @Column(name = "runner_up_score", updatable = false)
  private Double runnerUpScore;

  @Column(updatable = false)
  private double threshold;

  @Column(name = "matched_entry_id", updatable = false)
  private String matchedEntryId;

  @Column(name = "matched_entry_age_seconds", updatable = false)
  private Long matchedEntryAgeSeconds;

  @Column(name = "origin_correlation_id", updatable = false, length = 64)
  private String originCorrelationId;

  @Column(name = "embedding_model", updatable = false)
  private String embeddingModel;

  protected CacheDecisionEntity() {
    // JPA requires a no-arg constructor
  }

  CacheDecisionEntity(CacheDecision decision) {
    this.id = decision.id();
    this.correlationId = decision.correlationId();
    this.createdAt = decision.createdAt();
    this.promptHash = decision.promptHash();
    this.outcome = decision.outcome();
    this.similarityScore = decision.similarityScore();
    this.runnerUpScore = decision.runnerUpScore();
    this.threshold = decision.threshold();
    this.matchedEntryId = decision.matchedEntryId();
    this.matchedEntryAgeSeconds = decision.matchedEntryAgeSeconds();
    this.originCorrelationId = decision.originCorrelationId();
    this.embeddingModel = decision.embeddingModel();
  }

  CacheDecision toDomain() {
    return new CacheDecision(
        id, correlationId, createdAt, promptHash, outcome,
        similarityScore, runnerUpScore, threshold,
        matchedEntryId, matchedEntryAgeSeconds, originCorrelationId,
        embeddingModel);
  }
}
