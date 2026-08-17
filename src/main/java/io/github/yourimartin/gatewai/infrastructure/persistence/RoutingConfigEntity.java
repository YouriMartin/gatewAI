package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.StoredRoutingConfig;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The single stored routing-config row (v3 lot B.1). Its id is a constant: there
 * is one live configuration, and the {@code routing_config_single_row} check
 * constraint says so at the database level too.
 */
@Entity
@Table(name = "routing_config")
class RoutingConfigEntity {

  /** The only legal id, matched by the table's check constraint. */
  static final int ROW_ID = 1;

  @Id
  private Integer id;

  @Column(nullable = false)
  private long revision;

  @Column(nullable = false, length = 32)
  private String strategy;

  @Column(name = "entry_length_threshold", nullable = false)
  private int entryLengthThreshold;

  @Column(name = "premium_length_threshold", nullable = false)
  private int premiumLengthThreshold;

  /** JSONB rather than a child table: it is a list of strings, read whole. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "premium_keywords", nullable = false)
  private String premiumKeywords;

  @Column(name = "route_similarity_threshold", nullable = false)
  private double routeSimilarityThreshold;

  /**
   * The routes, JSONB. Ordered, because route order decides ties and
   * {@code RoutingConfigVersion} therefore hashes it.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String routes;

  @Column(name = "cascade_margin_band", nullable = false)
  private double cascadeMarginBand;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected RoutingConfigEntity() {
    // JPA requires a no-arg constructor. The id is a constant, so it is set
    // here rather than by a caller: Hibernate then overwrites it with the same
    // value on load, and a save can never invent a second row.
    this.id = ROW_ID;
  }

  /** Bumps the revision. Called on every write, identical values included. */
  void nextRevision(Instant now) {
    this.revision++;
    this.updatedAt = now;
  }

  void applyConfig(RoutingConfig config) {
    this.strategy = config.strategy();
    this.entryLengthThreshold = config.entryLengthThreshold();
    this.premiumLengthThreshold = config.premiumLengthThreshold();
    this.premiumKeywords =
        RoutingConfigJson.keywordsToJson(config.premiumKeywords());
    this.routeSimilarityThreshold = config.routeSimilarityThreshold();
    this.routes = RoutingConfigJson.routesToJson(config.routes());
  }

  void applyCascadeMarginBand(double band) {
    this.cascadeMarginBand = band;
  }

  StoredRoutingConfig toDomain() {
    return new StoredRoutingConfig(
        revision,
        new RoutingConfig(
            strategy,
            entryLengthThreshold,
            premiumLengthThreshold,
            RoutingConfigJson.keywordsFromJson(premiumKeywords),
            routeSimilarityThreshold,
            RoutingConfigJson.routesFromJson(routes)),
        cascadeMarginBand,
        updatedAt);
  }
}
