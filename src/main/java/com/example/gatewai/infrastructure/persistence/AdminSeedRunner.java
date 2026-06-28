package com.example.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import com.example.gatewai.domain.model.ApiClient;
import com.example.gatewai.domain.model.ApiKeyHasher;
import com.example.gatewai.domain.model.CreatedApiClient;
import com.example.gatewai.domain.port.in.ManageApiClientsUseCase;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the first admin client at startup so the system is usable without
 * hand-inserting a key (Phase 5.1).
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Configured key</b> — when {@code gatewai.admin.api-key}
 *       ({@code GATEWAI_ADMIN_API_KEY}) is set, an admin is seeded with that
 *       exact key. Idempotent: it is created only if no client already has that
 *       key's hash, so restarts are safe and the key is the one you chose (no
 *       "shown once" hunt in the logs).</li>
 *   <li><b>Random key</b> — otherwise, if no admin exists, one is created with a
 *       generated key that is logged exactly once. Copy it; it is never shown
 *       again.</li>
 * </ul>
 */
@Component
class AdminSeedRunner implements ApplicationRunner {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminSeedRunner.class);

  private static final String ADMIN_NAME = "bootstrap-admin";

  private final ApiClientRepository repository;
  private final ManageApiClientsUseCase manageApiClients;
  private final String configuredAdminKey;

  AdminSeedRunner(ApiClientRepository repository,
                  ManageApiClientsUseCase manageApiClients,
                  @Value("${gatewai.admin.api-key:}") String configuredAdminKey) {
    this.repository = repository;
    this.manageApiClients = manageApiClients;
    this.configuredAdminKey = configuredAdminKey;
  }

  @Override
  public void run(ApplicationArguments args) {
    String key = configuredAdminKey == null ? "" : configuredAdminKey.trim();
    if (!key.isEmpty()) {
      seedFromConfiguredKey(key);
      return;
    }
    seedRandom();
  }

  private void seedFromConfiguredKey(String rawKey) {
    String hash = ApiKeyHasher.hash(rawKey);
    if (repository.findByApiKeyHash(hash).isPresent()) {
      return; // idempotent: the configured admin key already exists
    }
    repository.save(new ApiClient(
        UUID.randomUUID(), ADMIN_NAME, hash, true, Instant.now(), true));
    LOG.info("Seeded admin client '{}' from GATEWAI_ADMIN_API_KEY.", ADMIN_NAME);
  }

  private void seedRandom() {
    if (repository.adminExists()) {
      return;
    }
    CreatedApiClient created = manageApiClients.create(ADMIN_NAME, true);
    LOG.warn("No admin client found — created '{}'. "
            + "Admin API key (shown ONCE, copy it now): {}",
        created.client().name(), created.rawKey());
  }
}
