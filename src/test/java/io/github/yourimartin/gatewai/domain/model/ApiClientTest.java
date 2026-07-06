package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApiClientTest {

  @Test
  void fieldsAreAccessible() {
    UUID id = UUID.randomUUID();
    Instant createdAt = Instant.now();
    ApiClient client = new ApiClient(
        id, "acme-corp", "a".repeat(64), true, createdAt, true
    );

    assertEquals(id, client.id());
    assertEquals("acme-corp", client.name());
    assertEquals("a".repeat(64), client.apiKeyHash());
    assertTrue(client.enabled());
    assertEquals(createdAt, client.createdAt());
    assertTrue(client.admin());
  }

  @Test
  void structuralEquality() {
    UUID id = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    ApiClient a = new ApiClient(id, "client", "b".repeat(64), true, createdAt, false);
    ApiClient b = new ApiClient(id, "client", "b".repeat(64), true, createdAt, false);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentFieldsProduceDifferentEquality() {
    UUID id = UUID.randomUUID();
    Instant createdAt = Instant.now();
    ApiClient a = new ApiClient(id, "client-a", "c".repeat(64), true, createdAt, false);
    ApiClient b = new ApiClient(id, "client-b", "c".repeat(64), true, createdAt, false);

    assertNotEquals(a, b);
  }
}
