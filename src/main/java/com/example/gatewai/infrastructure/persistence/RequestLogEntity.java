package com.example.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import com.example.gatewai.domain.model.GreenMetrics;
import com.example.gatewai.domain.model.RequestLog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "request_log")
class RequestLogEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(updatable = false, nullable = false)
  private Instant timestamp;

  @Column(updatable = false, nullable = false)
  private String model;

  @Column(name = "prompt_hash", updatable = false, nullable = false, length = 64)
  private String promptHash;

  @Column(name = "prompt_tokens", updatable = false, nullable = false)
  private int promptTokens;

  @Column(name = "completion_tokens", updatable = false, nullable = false)
  private int completionTokens;

  @Column(name = "total_tokens", updatable = false, nullable = false)
  private int totalTokens;

  @Column(name = "latency_ms", updatable = false, nullable = false)
  private long latencyMs;

  @Column(name = "client_id", updatable = false)
  private String clientId;

  @Column(name = "cost_eur", updatable = false)
  private double costEur;

  @Column(name = "energy_kwh", updatable = false)
  private double energyKwh;

  @Column(name = "grams_co2", updatable = false)
  private double gramsCo2;

  @Column(name = "grams_co2_avoided", updatable = false)
  private double gramsCo2Avoided;

  protected RequestLogEntity() {
    // JPA requires a no-arg constructor
  }

  RequestLogEntity(RequestLog log) {
    this.id = log.id();
    this.timestamp = log.timestamp();
    this.model = log.model();
    this.promptHash = log.promptHash();
    this.promptTokens = log.promptTokens();
    this.completionTokens = log.completionTokens();
    this.totalTokens = log.totalTokens();
    this.latencyMs = log.latencyMs();
    this.clientId = log.clientId();

    GreenMetrics green = log.green() != null ? log.green() : GreenMetrics.ZERO;
    this.costEur = green.costEur();
    this.energyKwh = green.energyKwh();
    this.gramsCo2 = green.gramsCo2();
    this.gramsCo2Avoided = green.gramsCo2Avoided();
  }

  RequestLog toDomain() {
    return new RequestLog(
        id, timestamp, model, promptHash,
        promptTokens, completionTokens, totalTokens, latencyMs,
        clientId,
        new GreenMetrics(costEur, energyKwh, gramsCo2, gramsCo2Avoided)
    );
  }
}
