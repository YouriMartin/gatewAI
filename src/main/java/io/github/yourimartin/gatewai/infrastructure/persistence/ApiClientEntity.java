package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ApiClient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
  }

  ApiClient toDomain() {
    return new ApiClient(id, name, apiKeyHash, enabled, createdAt, admin);
  }
}
