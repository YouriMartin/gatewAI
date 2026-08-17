package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ApiClient;
import io.github.yourimartin.gatewai.domain.model.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.CreatedApiClient;
import io.github.yourimartin.gatewai.domain.port.in.ManageApiClientsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AdminSeedRunnerTest {

  @Mock
  private ApiClientRepository repository;

  @Mock
  private ManageApiClientsUseCase manageApiClients;

  /** A lock this node always wins, so each test is about the seeding itself. */
  private static final LeaderLock GRANTED = (task, work) -> {
    work.run();
    return true;
  };

  /** A lock another node holds. */
  private static final LeaderLock DENIED = (task, work) -> false;

  // ---- Random-key mode (no configured key) ----

  @Test
  void createsRandomAdminWhenNoneExists() {
    AdminSeedRunner runner =
        new AdminSeedRunner(repository, manageApiClients, GRANTED, "");
    ApiClient admin = new ApiClient(
        UUID.randomUUID(), "bootstrap-admin", "h", true, Instant.now(), true);
    when(repository.adminExists()).thenReturn(false);
    when(manageApiClients.create("bootstrap-admin", true))
        .thenReturn(new CreatedApiClient(admin, "gw_raw"));

    runner.run(null);

    verify(manageApiClients).create("bootstrap-admin", true);
  }

  @Test
  void skipsWhenAdminAlreadyExists() {
    AdminSeedRunner runner =
        new AdminSeedRunner(repository, manageApiClients, GRANTED, null);
    when(repository.adminExists()).thenReturn(true);

    runner.run(null);

    verify(manageApiClients, never()).create(anyString(), anyBoolean());
  }

  // ---- Configured-key mode ----

  @Test
  void seedsAdminFromConfiguredKey() {
    String rawKey = "gw_chosen-key";
    AdminSeedRunner runner =
        new AdminSeedRunner(
            repository, manageApiClients, GRANTED, "  " + rawKey + "  ");
    when(repository.findByApiKeyHash(ApiKeyHasher.hash(rawKey)))
        .thenReturn(Optional.empty());

    runner.run(null);

    ArgumentCaptor<ApiClient> saved = ArgumentCaptor.forClass(ApiClient.class);
    verify(repository).save(saved.capture());
    assertEquals(ApiKeyHasher.hash(rawKey), saved.getValue().apiKeyHash());
    assertTrue(saved.getValue().admin());
    assertTrue(saved.getValue().enabled());
    verify(manageApiClients, never()).create(anyString(), anyBoolean());
  }

  // ---- Concurrent cold start (v3 lot B.4) ----

  @Test
  void aNodeThatDoesNotHoldTheLockSeedsNothing() {
    // Two instances booting together: only the one holding ADMIN_SEED writes.
    // Without this the random-key mode would create two admins with two
    // different keys — no constraint broken, and two "copy this now" log lines.
    AdminSeedRunner runner = new AdminSeedRunner(
        repository, manageApiClients, DENIED, "");

    runner.run(null);

    verify(repository, never()).save(any());
    verify(manageApiClients, never()).create(anyString(), anyBoolean());
  }

  @Test
  void losingTheUniqueKeyRaceDoesNotFailStartup() {
    // The insert joins the lock's transaction, so a unique violation on
    // api_key_hash arrives at commit — i.e. out of runIfLeader. Whatever else
    // that is, it is not a reason for a gateway to refuse to boot: the key it
    // wanted to create is already there.
    String rawKey = "gw_chosen-key";
    LeaderLock violating = (task, work) -> {
      work.run();
      throw new DataIntegrityViolationException(
          "duplicate key value violates unique constraint "
              + "\"api_client_api_key_hash_key\"");
    };
    AdminSeedRunner runner = new AdminSeedRunner(
        repository, manageApiClients, violating, rawKey);
    when(repository.findByApiKeyHash(ApiKeyHasher.hash(rawKey)))
        .thenReturn(Optional.empty());

    assertDoesNotThrow(() -> runner.run(null));
  }

  @Test
  void configuredKeyIsIdempotent() {
    String rawKey = "gw_chosen-key";
    AdminSeedRunner runner =
        new AdminSeedRunner(repository, manageApiClients, GRANTED, rawKey);
    ApiClient existing = new ApiClient(
        UUID.randomUUID(), "bootstrap-admin", ApiKeyHasher.hash(rawKey),
        true, Instant.now(), true);
    when(repository.findByApiKeyHash(ApiKeyHasher.hash(rawKey)))
        .thenReturn(Optional.of(existing));

    runner.run(null);

    verify(repository, never()).save(any());
  }
}
