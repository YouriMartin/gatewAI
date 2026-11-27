package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.carbon.CarbonZoneSource;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergySource;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.carbon.RegionProvenance;
import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.shadow.CounterfactualOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.ShadowCounterfactual;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One shadow request's counterfactual (v4 D.4, ADR 0020), as priced by the replay.
 * Written once, never updated: like {@code request_log}, a row describes the
 * configuration it was priced under, not the current one.
 */
@Entity
@Table(name = "shadow_counterfactual")
class ShadowCounterfactualEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(name = "correlation_id", updatable = false, nullable = false, length = 64)
  private String correlationId;

  @Column(name = "client_id", updatable = false)
  private String clientId;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false, nullable = false, length = 16)
  private CounterfactualOutcome outcome;

  @Column(updatable = false)
  private String model;

  @Column(name = "prompt_tokens", updatable = false, nullable = false)
  private int promptTokens;

  @Column(name = "completion_tokens", updatable = false, nullable = false)
  private int completionTokens;

  @Column(name = "total_tokens", updatable = false, nullable = false)
  private int totalTokens;

  @Column(name = "cached_prompt_tokens", updatable = false)
  private Integer cachedPromptTokens;

  @Column(updatable = false, nullable = false)
  private double cost;

  @Enumerated(EnumType.STRING)
  @Column(name = "pricing_basis", updatable = false, nullable = false, length = 16)
  private PricingBasis pricingBasis;

  @Column(updatable = false, nullable = false, length = 3)
  private String currency;

  @Column(name = "energy_kwh", updatable = false, nullable = false)
  private double energyKwh;

  @Column(name = "grams_co2", updatable = false, nullable = false)
  private double gramsCo2;

  @Column(updatable = false)
  private String provider;

  @Column(name = "grid_zone", updatable = false, length = 64)
  private String gridZone;

  @Column(name = "grid_intensity_g_per_kwh", updatable = false)
  private Double gridIntensityGramsPerKwh;

  @Enumerated(EnumType.STRING)
  @Column(name = "grid_zone_source", updatable = false, length = 32)
  private CarbonZoneSource gridZoneSource;

  @Column(name = "dispatch_zone", updatable = false, length = 64)
  private String dispatchZone;

  @Enumerated(EnumType.STRING)
  @Column(name = "region_provenance", updatable = false, length = 16)
  private RegionProvenance regionProvenance;

  @Enumerated(EnumType.STRING)
  @Column(name = "energy_source", updatable = false, length = 32)
  private EnergySource energySource;

  @Column(updatable = false)
  private Double pue;

  protected ShadowCounterfactualEntity() {
    // JPA requires a no-arg constructor
  }

  ShadowCounterfactualEntity(ShadowCounterfactual counterfactual) {
    this.id = counterfactual.id();
    this.correlationId = counterfactual.correlationId();
    this.clientId = counterfactual.clientId();
    this.createdAt = counterfactual.createdAt();
    this.outcome = counterfactual.outcome();
    this.model = counterfactual.model();
    TokenUsage usage = counterfactual.usage();
    this.promptTokens = usage.promptTokens();
    this.completionTokens = usage.completionTokens();
    this.totalTokens = usage.totalTokens();
    this.cachedPromptTokens = usage.cachedPromptTokens();
    GreenMetrics metrics = counterfactual.metrics();
    this.cost = metrics.cost();
    this.pricingBasis = metrics.pricingBasis();
    this.currency = metrics.currency();
    this.energyKwh = metrics.energyKwh();
    this.gramsCo2 = metrics.gramsCo2();
    GreenProvenance provenance = counterfactual.provenance();
    this.provider = provenance.provider();
    this.gridZone = provenance.gridZone();
    this.gridIntensityGramsPerKwh = provenance.gridIntensityGramsPerKwh();
    this.gridZoneSource = provenance.gridZoneSource();
    this.dispatchZone = provenance.dispatchZone();
    this.regionProvenance = provenance.regionProvenance();
    this.energySource = provenance.energySource();
    this.pue = provenance.pue();
  }

  ShadowCounterfactual toDomain() {
    return new ShadowCounterfactual(id, correlationId, clientId, createdAt, outcome, model,
        new TokenUsage(promptTokens, completionTokens, totalTokens, cachedPromptTokens),
        new GreenMetrics(cost, energyKwh, gramsCo2, 0.0, 0.0, pricingBasis, currency),
        new GreenProvenance(provider, gridZone,
            gridIntensityGramsPerKwh == null ? 0.0 : gridIntensityGramsPerKwh,
            gridZoneSource, dispatchZone, regionProvenance, energySource, pue));
  }
}
