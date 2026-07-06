package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

@ExtendWith(MockitoExtension.class)
class AdminSeedRunnerTest {

  @Mock
  private ApiClientRepository repository;

  @Mock
  private ManageApiClientsUseCase manageApiClients;

  // ---- Random-key mode (no configured key) ----

  @Test
  void createsRandomAdminWhenNoneExists() {
    AdminSeedRunner runner =
        new AdminSeedRunner(repository, manageApiClients, "");
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
        new AdminSeedRunner(repository, manageApiClients, null);
    when(repository.adminExists()).thenReturn(true);

    runner.run(null);

    verify(manageApiClients, never()).create(anyString(), anyBoolean());
  }

  // ---- Configured-key mode ----

  @Test
  void seedsAdminFromConfiguredKey() {
    String rawKey = "gw_chosen-key";
    AdminSeedRunner runner =
        new AdminSeedRunner(repository, manageApiClients, "  " + rawKey + "  ");
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

  @Test
  void configuredKeyIsIdempotent() {
    String rawKey = "gw_chosen-key";
    AdminSeedRunner runner =
        new AdminSeedRunner(repository, manageApiClients, rawKey);
    ApiClient existing = new ApiClient(
        UUID.randomUUID(), "bootstrap-admin", ApiKeyHasher.hash(rawKey),
        true, Instant.now(), true);
    when(repository.findByApiKeyHash(ApiKeyHasher.hash(rawKey)))
        .thenReturn(Optional.of(existing));

    runner.run(null);

    verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
