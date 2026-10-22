package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.carbon.CarbonZoneSource;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergySource;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;
import io.github.yourimartin.gatewai.domain.model.carbon.RegionProvenance;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "request_log")
class RequestLogEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(name = "correlation_id", updatable = false, length = 64)
  private String correlationId;

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

  /** In {@link #currency}; the column was {@code cost_eur} until v4 B.6. */
  @Column(name = "cost", updatable = false)
  private double cost;

  @Column(name = "energy_kwh", updatable = false)
  private double energyKwh;

  @Column(name = "grams_co2", updatable = false)
  private double gramsCo2;

  @Column(name = "cost_avoided", updatable = false)
  private double costAvoided;

  @Column(name = "grams_co2_avoided", updatable = false)
  private double gramsCo2Avoided;

  @Column(name = "cache_hit", updatable = false)
  private boolean cacheHit;

  // --- Pricing (v4 B.6): what the two costs are in and how they were priced.
  // Rows written before B.6 read BLENDED, EUR, and no cached-token count.

  @Column(name = "cached_prompt_tokens", updatable = false)
  private Integer cachedPromptTokens;

  @Enumerated(EnumType.STRING)
  @Column(name = "pricing_basis", updatable = false, nullable = false, length = 16)
  private PricingBasis pricingBasis;

  @Column(name = "currency", updatable = false, nullable = false, length = 3)
  private String currency;

  // --- Green provenance (v3 lot C.5): what the figures above were derived from,
  // so a row explains itself without consulting today's configuration. Nullable
  // throughout, because rows written before C.5 have none.

  @Column(name = "provider", updatable = false)
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

  @Column(name = "pue", updatable = false)
  private Double pue;

  protected RequestLogEntity() {
    // JPA requires a no-arg constructor
  }

  RequestLogEntity(RequestLog log) {
    this.id = log.id();
    this.correlationId = log.correlationId();
    this.timestamp = log.timestamp();
    this.model = log.model();
    this.promptHash = log.promptHash();
    this.promptTokens = log.promptTokens();
    this.completionTokens = log.completionTokens();
    this.totalTokens = log.totalTokens();
    this.latencyMs = log.latencyMs();
    this.clientId = log.clientId();
    this.cacheHit = log.cacheHit();

    this.cachedPromptTokens = log.cachedPromptTokens();

    // Production always logs an accounting, if only to say nothing was priced; a row
    // without one has no currency, which the NOT NULL column then refuses.
    GreenMetrics green = log.green() != null ? log.green() : GreenMetrics.none(null);
    this.cost = green.cost();
    this.energyKwh = green.energyKwh();
    this.gramsCo2 = green.gramsCo2();
    this.costAvoided = green.costAvoided();
    this.gramsCo2Avoided = green.gramsCo2Avoided();
    this.pricingBasis = green.pricingBasis();
    this.currency = green.currency();

    GreenProvenance provenance = log.provenance();
    this.provider = provenance.provider();
    this.gridZone = provenance.gridZone();
    this.gridIntensityGramsPerKwh = provenance.gridIntensityGramsPerKwh();
    this.gridZoneSource = provenance.gridZoneSource();
    this.dispatchZone = provenance.dispatchZone();
    this.regionProvenance = provenance.regionProvenance();
    this.energySource = provenance.energySource();
    this.pue = provenance.pue();
  }

  RequestLog toDomain() {
    return new RequestLog(
        id, correlationId, timestamp, model, promptHash,
        promptTokens, completionTokens, totalTokens, latencyMs,
        clientId,
        new GreenMetrics(cost, energyKwh, gramsCo2,
            costAvoided, gramsCo2Avoided, pricingBasis, currency),
        provenance(),
        cacheHit,
        cachedPromptTokens
    );
  }

  /**
   * A pre-C.5 row has no provenance columns at all; it comes back as
   * {@link GreenProvenance#UNKNOWN} rather than as a fabricated attribution.
   */
  private GreenProvenance provenance() {
    if (gridIntensityGramsPerKwh == null && provider == null && energySource == null) {
      return GreenProvenance.UNKNOWN;
    }
    return new GreenProvenance(provider, gridZone,
        gridIntensityGramsPerKwh == null ? 0.0 : gridIntensityGramsPerKwh,
        gridZoneSource, dispatchZone, regionProvenance, energySource, pue);
  }
}
