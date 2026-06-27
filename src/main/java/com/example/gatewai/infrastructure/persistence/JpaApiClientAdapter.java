package com.example.gatewai.infrastructure.persistence;

import java.util.Optional;

import com.example.gatewai.domain.model.ApiClient;
import com.example.gatewai.domain.port.out.ApiClientRepository;

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
}
