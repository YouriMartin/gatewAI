package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ApiClient;
import io.github.yourimartin.gatewai.domain.model.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.CreatedApiClient;
import io.github.yourimartin.gatewai.domain.port.in.ManageApiClientsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
 *
 * <p>Two instances starting together used to be a problem in both modes, and
 * v3 lot B.4 closes it twice over because the two failures are different:
 * <ul>
 *   <li>The whole seeding runs under a {@link LeaderLock}, so exactly one node
 *       seeds. This is what the <b>random</b> mode needs — two nodes generating
 *       two different keys break no constraint, they just produce two admins and
 *       two "copy this now" log lines, only one of which anybody reads.</li>
 *   <li>The insert still catches the {@code api_key_hash} unique violation. That
 *       covers the <b>configured</b> mode against anything the lock does not see:
 *       a key inserted by hand, or a future startup path that forgets to take it.
 *       Losing that race must not fail a boot — the key is already there, which
 *       is the outcome this runner wanted.</li>
 * </ul>
 */
@Component
class AdminSeedRunner implements ApplicationRunner {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminSeedRunner.class);

  private static final String ADMIN_NAME = "bootstrap-admin";

  private final ApiClientRepository repository;
  private final ManageApiClientsUseCase manageApiClients;
  private final LeaderLock leaderLock;
  private final String configuredAdminKey;

  AdminSeedRunner(ApiClientRepository repository,
                  ManageApiClientsUseCase manageApiClients,
                  LeaderLock leaderLock,
                  @Value("${gatewai.admin.api-key:}") String configuredAdminKey) {
    this.repository = repository;
    this.manageApiClients = manageApiClients;
    this.leaderLock = leaderLock;
    this.configuredAdminKey = configuredAdminKey;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      if (!leaderLock.runIfLeader(LeaderTask.ADMIN_SEED, this::seed)) {
        LOG.info("Another instance is seeding the admin client; skipping.");
      }
    } catch (DataIntegrityViolationException e) {
      // Caught here rather than around the insert, because that is where it can
      // actually arrive: the insert joins the lock's transaction, so a unique
      // violation on api_key_hash surfaces at *commit*, one frame further out.
      // Losing that race means the key is already there — the outcome this
      // runner wanted — and a gateway must not refuse to start over it.
      LOG.info("An admin client with that key already exists; nothing seeded.");
    }
  }

  private void seed() {
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
