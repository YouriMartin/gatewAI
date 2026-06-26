package com.example.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RequestLogTest {

  @Test
  void fieldsAreAccessible() {
    UUID id = UUID.randomUUID();
    Instant timestamp = Instant.now();
    RequestLog log = new RequestLog(
        id, timestamp, "claude-3", "abc123", 10, 5, 15, 42L
    );

    assertEquals(id, log.id());
    assertEquals(timestamp, log.timestamp());
    assertEquals("claude-3", log.model());
    assertEquals("abc123", log.promptHash());
    assertEquals(10, log.promptTokens());
    assertEquals(5, log.completionTokens());
    assertEquals(15, log.totalTokens());
    assertEquals(42L, log.latencyMs());
  }

  @Test
  void structuralEquality() {
    UUID id = UUID.randomUUID();
    Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
    RequestLog log1 = new RequestLog(
        id, timestamp, "claude-3", "hash", 10, 5, 15, 100L
    );
    RequestLog log2 = new RequestLog(
        id, timestamp, "claude-3", "hash", 10, 5, 15, 100L
    );

    assertEquals(log1, log2);
    assertEquals(log1.hashCode(), log2.hashCode());
  }

  @Test
  void differentFieldsProduceDifferentEquality() {
    UUID id = UUID.randomUUID();
    Instant timestamp = Instant.now();
    RequestLog log1 = new RequestLog(
        id, timestamp, "claude-3", "hash1", 10, 5, 15, 100L
    );
    RequestLog log2 = new RequestLog(
        id, timestamp, "claude-3", "hash2", 10, 5, 15, 100L
    );

    assertNotEquals(log1, log2);
  }
}
