package com.example.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.gatewai.domain.model.ApiClient;

public interface ApiClientRepository {

  Optional<ApiClient> findByApiKeyHash(String apiKeyHash);

  ApiClient save(ApiClient client);

  List<ApiClient> findAll();

  Optional<ApiClient> findById(UUID id);

  boolean adminExists();
}
