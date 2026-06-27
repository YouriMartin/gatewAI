package com.example.gatewai.domain.port.out;

import java.util.Optional;

import com.example.gatewai.domain.model.ApiClient;

public interface ApiClientRepository {

  Optional<ApiClient> findByApiKeyHash(String apiKeyHash);
}
