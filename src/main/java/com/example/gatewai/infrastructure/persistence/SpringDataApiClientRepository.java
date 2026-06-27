package com.example.gatewai.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataApiClientRepository extends JpaRepository<ApiClientEntity, UUID> {

  Optional<ApiClientEntity> findByApiKeyHash(String apiKeyHash);
}
