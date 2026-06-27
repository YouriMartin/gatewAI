package com.example.gatewai.infrastructure.persistence;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import com.example.gatewai.domain.model.ApiClient;
import com.example.gatewai.domain.model.CreatedApiClient;
import com.example.gatewai.domain.port.in.ManageApiClientsUseCase;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminSeedRunnerTest {

  @Mock
  private ApiClientRepository repository;

  @Mock
  private ManageApiClientsUseCase manageApiClients;

  @InjectMocks
  private AdminSeedRunner runner;

  @Test
  void createsAdminWhenNoneExists() {
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
    when(repository.adminExists()).thenReturn(true);

    runner.run(null);

    verify(manageApiClients, never()).create(anyString(), anyBoolean());
  }
}
