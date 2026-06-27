package com.example.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.gatewai.domain.model.ApiClient;
import com.example.gatewai.domain.model.ApiKeyHasher;
import com.example.gatewai.domain.model.CreatedApiClient;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiClientAdminServiceTest {

  @Mock
  private ApiClientRepository repository;

  private ApiClientAdminService service;

  @BeforeEach
  void setUp() {
    service = new ApiClientAdminService(repository);
  }

  @Test
  void createGeneratesKeyStoresHashAndReturnsRawKeyOnce() {
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    CreatedApiClient created = service.create("acme", true);

    assertTrue(created.rawKey().startsWith("gw_"));
    assertTrue(created.client().admin());
    assertTrue(created.client().enabled());
    // only the hash is stored, and it matches the raw key
    assertEquals(ApiKeyHasher.hash(created.rawKey()),
        created.client().apiKeyHash());
    assertNotEquals(created.rawKey(), created.client().apiKeyHash());
    verify(repository).save(any());
  }

  @Test
  void createGeneratesDistinctKeys() {
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    CreatedApiClient first = service.create("a", false);
    CreatedApiClient second = service.create("b", false);

    assertNotEquals(first.rawKey(), second.rawKey());
  }

  @Test
  void listDelegatesToRepository() {
    ApiClient client = new ApiClient(
        UUID.randomUUID(), "a", "h", true, Instant.now(), false);
    when(repository.findAll()).thenReturn(List.of(client));

    assertEquals(List.of(client), service.list());
  }

  @Test
  void revokeDisablesEnabledClient() {
    UUID id = UUID.randomUUID();
    ApiClient client = new ApiClient(id, "a", "h", true, Instant.now(), false);
    when(repository.findById(id)).thenReturn(Optional.of(client));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.revoke(id);

    ArgumentCaptor<ApiClient> captor = ArgumentCaptor.forClass(ApiClient.class);
    verify(repository).save(captor.capture());
    assertFalse(captor.getValue().enabled());
  }

  @Test
  void revokeIgnoresUnknownId() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    service.revoke(id);

    verify(repository, never()).save(any());
  }
}
