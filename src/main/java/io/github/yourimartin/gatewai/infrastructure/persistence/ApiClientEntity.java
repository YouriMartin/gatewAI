package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.client.ApiClient;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "api_client")
class ApiClientEntity {

  @Id
  @Column(updatable = false)
  private UUID id;

  @Column(updatable = false, nullable = false)
  private String name;

  @Column(name = "api_key_hash", updatable = false, nullable = false, length = 64, unique = true)
  private String apiKeyHash;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", updatable = false, nullable = false)
  private Instant createdAt;

  @Column(name = "admin", updatable = false, nullable = false)
  private boolean admin;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private ClientMode mode;

  @Column(name = "mode_changed_at")
  private Instant modeChangedAt;

  @Column(name = "mode_changed_by", length = 64)
  private String modeChangedBy;

  protected ApiClientEntity() {
    // JPA requires a no-arg constructor
  }

  ApiClientEntity(ApiClient client) {
    this.id = client.id();
    this.name = client.name();
    this.apiKeyHash = client.apiKeyHash();
    this.enabled = client.enabled();
    this.createdAt = client.createdAt();
    this.admin = client.admin();
    this.mode = client.mode();
    this.modeChangedAt = client.modeChangedAt();
    this.modeChangedBy = client.modeChangedBy();
  }

  ApiClient toDomain() {
    return new ApiClient(id, name, apiKeyHash, enabled, createdAt, admin, mode,
        modeChangedAt, modeChangedBy);
  }
}
