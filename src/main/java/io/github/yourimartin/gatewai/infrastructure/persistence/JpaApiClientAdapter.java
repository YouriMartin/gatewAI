package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ApiClient;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.springframework.stereotype.Component;

@Component
class JpaApiClientAdapter implements ApiClientRepository {

  private final SpringDataApiClientRepository jpaRepository;

  JpaApiClientAdapter(SpringDataApiClientRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Optional<ApiClient> findByApiKeyHash(String apiKeyHash) {
    return jpaRepository.findByApiKeyHash(apiKeyHash)
        .map(ApiClientEntity::toDomain);
  }

  @Override
  public ApiClient save(ApiClient client) {
    return jpaRepository.save(new ApiClientEntity(client)).toDomain();
  }

  @Override
  public List<ApiClient> findAll() {
    return jpaRepository.findAll().stream()
        .map(ApiClientEntity::toDomain)
        .toList();
  }

  @Override
  public Optional<ApiClient> findById(UUID id) {
    return jpaRepository.findById(id).map(ApiClientEntity::toDomain);
  }

  @Override
  public boolean adminExists() {
    return jpaRepository.existsByAdminTrue();
  }
}
