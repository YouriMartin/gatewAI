package io.github.yourimartin.gatewai.application.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ApiClient;
import io.github.yourimartin.gatewai.domain.model.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.CreatedApiClient;
import io.github.yourimartin.gatewai.domain.port.in.ManageApiClientsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.springframework.stereotype.Service;

/**
 * Admin operations on API clients (Phase 5.1). Generates a random key, stores
 * only its hash, and returns the raw key once at creation.
 */
@Service
class ApiClientAdminService implements ManageApiClientsUseCase {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int KEY_BYTES = 32;

  private final ApiClientRepository repository;

  ApiClientAdminService(ApiClientRepository repository) {
    this.repository = repository;
  }

  @Override
  public CreatedApiClient create(String name, boolean admin) {
    String rawKey = generateKey();
    ApiClient client = new ApiClient(
        UUID.randomUUID(), name, ApiKeyHasher.hash(rawKey),
        true, Instant.now(), admin);
    return new CreatedApiClient(repository.save(client), rawKey);
  }

  @Override
  public List<ApiClient> list() {
    return repository.findAll();
  }

  @Override
  public void revoke(UUID id) {
    repository.findById(id)
        .filter(ApiClient::enabled)
        .ifPresent(client -> repository.save(new ApiClient(
            client.id(), client.name(), client.apiKeyHash(),
            false, client.createdAt(), client.admin())));
  }

  private static String generateKey() {
    byte[] bytes = new byte[KEY_BYTES];
    RANDOM.nextBytes(bytes);
    return "gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
