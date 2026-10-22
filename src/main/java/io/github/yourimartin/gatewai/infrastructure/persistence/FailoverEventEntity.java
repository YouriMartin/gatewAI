package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.decision.FailoverEvent;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverCause;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A request moving to the next entry of its tier (ADR 0017, v4 B.5). */
@Entity
@Table(name = "failover_event")
class FailoverEventEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(name = "correlation_id", updatable = false, length = 64)
  private String correlationId;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, nullable = false, length = 16)
  private FailoverEvent.Path path;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, nullable = false, length = 32)
  private ModelTier tier;

  @Column(name = "from_model_id", updatable = false, nullable = false)
  private String fromModelId;

  @Column(name = "to_model_id", updatable = false)
  private String toModelId;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, nullable = false, length = 32)
  private FailoverCause cause;

  @Column(name = "upstream_status", updatable = false)
  private Integer upstreamStatus;

  protected FailoverEventEntity() {
    // JPA requires a no-arg constructor
  }

  FailoverEventEntity(FailoverEvent event) {
    this.id = event.id();
    this.correlationId = event.correlationId();
    this.createdAt = event.createdAt();
    this.path = event.path();
    this.tier = event.tier();
    this.fromModelId = event.fromModelId();
    this.toModelId = event.toModelId();
    this.cause = event.cause();
    this.upstreamStatus = event.upstreamStatus();
  }
}
