package io.github.yourimartin.gatewai.application.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.client.ApiClient;
import io.github.yourimartin.gatewai.domain.model.client.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.client.CreatedApiClient;
import io.github.yourimartin.gatewai.domain.port.in.ManageApiClientsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Admin operations on API clients (Phase 5.1). Generates a random key, stores
 * only its hash, and returns the raw key once at creation.
 */
@Service
class ApiClientAdminService implements ManageApiClientsUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(ApiClientAdminService.class);
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
        .ifPresent(client -> repository.save(client.revoked()));
  }

  /**
   * Records who changed the mode and when, on the client row and in the log. A
   * request for the mode the client is already in changes nothing, and so
   * records nothing.
   */
  @Override
  public Optional<ApiClient> changeMode(UUID id, ClientMode mode, String adminId) {
    return repository.findById(id).map(client -> {
      if (client.mode() == mode) {
        return client;
      }
      ApiClient changed = repository.save(client.withMode(mode, adminId, Instant.now()));
      LOG.info("Client {} ('{}') moved from {} to {} by admin client {}",
          client.id(), client.name(), client.mode(), mode, adminId);
      return changed;
    });
  }

  private static String generateKey() {
    byte[] bytes = new byte[KEY_BYTES];
    RANDOM.nextBytes(bytes);
    return "gw_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
