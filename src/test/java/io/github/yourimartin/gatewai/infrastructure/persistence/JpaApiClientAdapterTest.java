package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ApiClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaApiClientAdapterTest {

  @Mock
  private SpringDataApiClientRepository jpaRepository;

  @InjectMocks
  private JpaApiClientAdapter adapter;

  @Test
  void findByApiKeyHashDelegatesToSpringData() {
    String hash = "a".repeat(64);
    ApiClient client = new ApiClient(
        UUID.randomUUID(), "test-client", hash, true, Instant.now(), false
    );
    ApiClientEntity entity = new ApiClientEntity(client);

    when(jpaRepository.findByApiKeyHash(hash)).thenReturn(Optional.of(entity));

    Optional<ApiClient> result = adapter.findByApiKeyHash(hash);

    assertTrue(result.isPresent());
    assertEquals("test-client", result.get().name());
    verify(jpaRepository).findByApiKeyHash(hash);
  }

  @Test
  void findByApiKeyHashReturnsEmptyWhenNotFound() {
    String hash = "b".repeat(64);
    when(jpaRepository.findByApiKeyHash(hash)).thenReturn(Optional.empty());

    Optional<ApiClient> result = adapter.findByApiKeyHash(hash);

    assertTrue(result.isEmpty());
  }

  @Test
  void roundTripDomainToEntityToDomain() {
    ApiClient original = new ApiClient(
        UUID.randomUUID(), "acme", "c".repeat(64),
        true, Instant.parse("2026-06-01T12:00:00Z"), true
    );

    ApiClientEntity entity = new ApiClientEntity(original);
    ApiClient restored = entity.toDomain();

    assertEquals(original, restored);
  }

  @Test
  void adminExistsDelegatesToSpringData() {
    when(jpaRepository.existsByAdminTrue()).thenReturn(true);

    assertTrue(adapter.adminExists());
    verify(jpaRepository).existsByAdminTrue();
  }
}
