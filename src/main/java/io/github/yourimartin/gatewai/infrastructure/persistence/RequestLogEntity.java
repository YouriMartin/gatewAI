package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CarbonZoneSource;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.RegionProvenance;
import io.github.yourimartin.gatewai.domain.model.RequestLog;

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

  @Column(name = "cost_eur", updatable = false)
  private double costEur;

  @Column(name = "energy_kwh", updatable = false)
  private double energyKwh;

  @Column(name = "grams_co2", updatable = false)
  private double gramsCo2;

  @Column(name = "cost_avoided_eur", updatable = false)
  private double costAvoidedEur;

  @Column(name = "grams_co2_avoided", updatable = false)
  private double gramsCo2Avoided;

  @Column(name = "cache_hit", updatable = false)
  private boolean cacheHit;

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

    GreenMetrics green = log.green() != null ? log.green() : GreenMetrics.ZERO;
    this.costEur = green.costEur();
    this.energyKwh = green.energyKwh();
    this.gramsCo2 = green.gramsCo2();
    this.costAvoidedEur = green.costAvoidedEur();
    this.gramsCo2Avoided = green.gramsCo2Avoided();

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
        new GreenMetrics(costEur, energyKwh, gramsCo2,
            costAvoidedEur, gramsCo2Avoided),
        provenance(),
        cacheHit
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
