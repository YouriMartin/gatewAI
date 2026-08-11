package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "routing_decision")
class RoutingDecisionEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(name = "correlation_id", updatable = false, length = 64)
  private String correlationId;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "prompt_hash", updatable = false, nullable = false, length = 64)
  private String promptHash;

  @Column(name = "prompt_length", updatable = false, nullable = false)
  private int promptLength;

  @Column(name = "embedding_model", updatable = false)
  private String embeddingModel;

  @Column(name = "routing_config_version", updatable = false, length = 64)
  private String routingConfigVersion;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, length = 32)
  private ClassificationStrategy strategy;

  @Enumerated(EnumType.STRING)
  @Column(name = "effective_strategy", updatable = false, length = 32)
  private ClassificationStrategy effectiveStrategy;

  /** The full justification; JSONB so it stays queryable without a schema. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(updatable = false)
  private String justification;

  @Enumerated(EnumType.STRING)
  @Column(name = "decision_reason", updatable = false, length = 32)
  private DecisionReason decisionReason;

  @Enumerated(EnumType.STRING)
  @Column(name = "chosen_tier", updatable = false, length = 32)
  private ModelTier chosenTier;

  @Column(name = "chosen_model_id", updatable = false)
  private String chosenModelId;

  @Column(name = "routing_latency_ms", updatable = false)
  private long routingLatencyMs;

  /**
   * The prediction set, joined by commas. Null when no calibration applied —
   * distinct from an empty string, which is a calibrated set that stayed empty.
   */
  @Column(name = "conformal_set", updatable = false)
  private String conformalSet;

  @Column(name = "conformal_alpha", updatable = false)
  private Double conformalAlpha;

  protected RoutingDecisionEntity() {
    // JPA requires a no-arg constructor
  }

  RoutingDecisionEntity(RoutingDecision decision) {
    this.id = decision.id();
    this.correlationId = decision.correlationId();
    this.createdAt = decision.createdAt();
    this.promptHash = decision.promptHash();
    this.promptLength = decision.promptLength();
    this.embeddingModel = decision.embeddingModel();
    this.routingConfigVersion = decision.routingConfigVersion();
    this.strategy = decision.strategy();
    this.effectiveStrategy = decision.effectiveStrategy();
    this.justification = JustificationJson.toJson(decision.justification());
    this.decisionReason = decision.decisionReason();
    this.chosenTier = decision.chosenTier();
    this.chosenModelId = decision.chosenModelId();
    this.routingLatencyMs = decision.routingLatencyMs();
    this.conformalSet = decision.conformalSet() == null ? null
        : decision.conformalSet().stream().map(Enum::name)
            .collect(Collectors.joining(","));
    this.conformalAlpha = decision.conformalAlpha();
  }

  RoutingDecision toDomain() {
    return new RoutingDecision(
        id, correlationId, createdAt, promptHash, promptLength,
        embeddingModel, routingConfigVersion, strategy, effectiveStrategy,
        JustificationJson.fromJson(justification), decisionReason,
        chosenTier, chosenModelId, routingLatencyMs,
        conformalSetToDomain(), conformalAlpha);
  }

  private List<ModelTier> conformalSetToDomain() {
    if (conformalSet == null) {
      return null;
    }
    if (conformalSet.isBlank()) {
      return List.of();
    }
    return Arrays.stream(conformalSet.split(","))
        .map(ModelTier::valueOf)
        .toList();
  }
}
