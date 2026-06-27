package com.example.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RequestLogTest {

  @Test
  void fieldsAreAccessible() {
    UUID id = UUID.randomUUID();
    Instant timestamp = Instant.now();
    RequestLog log = new RequestLog(
        id, timestamp, "claude-3", "abc123", 10, 5, 15, 42L, "client-1"
    );

    assertEquals(id, log.id());
    assertEquals(timestamp, log.timestamp());
    assertEquals("claude-3", log.model());
    assertEquals("abc123", log.promptHash());
    assertEquals(10, log.promptTokens());
    assertEquals(5, log.completionTokens());
    assertEquals(15, log.totalTokens());
    assertEquals(42L, log.latencyMs());
    assertEquals("client-1", log.clientId());
  }

  @Test
  void clientIdCanBeNull() {
    RequestLog log = new RequestLog(
        UUID.randomUUID(), Instant.now(), "claude-3", "abc123",
        10, 5, 15, 42L, null
    );

    assertNull(log.clientId());
  }

  @Test
  void structuralEquality() {
    UUID id = UUID.randomUUID();
    Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
    RequestLog log1 = new RequestLog(
        id, timestamp, "claude-3", "hash", 10, 5, 15, 100L, "client-1"
    );
    RequestLog log2 = new RequestLog(
        id, timestamp, "claude-3", "hash", 10, 5, 15, 100L, "client-1"
    );

    assertEquals(log1, log2);
    assertEquals(log1.hashCode(), log2.hashCode());
  }

  @Test
  void differentFieldsProduceDifferentEquality() {
    UUID id = UUID.randomUUID();
    Instant timestamp = Instant.now();
    RequestLog log1 = new RequestLog(
        id, timestamp, "claude-3", "hash1", 10, 5, 15, 100L, "client-1"
    );
    RequestLog log2 = new RequestLog(
        id, timestamp, "claude-3", "hash2", 10, 5, 15, 100L, "client-1"
    );

    assertNotEquals(log1, log2);
  }
}
