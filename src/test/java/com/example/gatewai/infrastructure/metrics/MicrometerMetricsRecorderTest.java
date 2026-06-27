package com.example.gatewai.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;

import com.example.gatewai.domain.model.GreenMetrics;
import com.example.gatewai.domain.model.RequestLog;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MicrometerMetricsRecorderTest {

  private static final double DELTA = 1e-9;

  private SimpleMeterRegistry registry;
  private MicrometerMetricsRecorder recorder;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    recorder = new MicrometerMetricsRecorder(registry);
  }

  private static RequestLog log(String model, int tokens, boolean cacheHit,
                                GreenMetrics green) {
    return new RequestLog(UUID.randomUUID(), Instant.now(), model, "hash",
        1, 1, tokens, 42L, "client", green, cacheHit);
  }

  @Test
  void recordsCountersAndTimerForAMiss() {
    recorder.record(log("sonnet", 20, false,
        new GreenMetrics(0.015, 0.004, 1.15, 0.0, 0.69)));

    assertEquals(1.0, registry.get("gatewai.requests")
        .tags("model", "sonnet", "cache_hit", "false").counter().count(), DELTA);
    assertEquals(20.0, registry.get("gatewai.tokens")
        .tags("model", "sonnet").counter().count(), DELTA);
    assertEquals(0.015, registry.get("gatewai.cost.eur")
        .tags("model", "sonnet").counter().count(), DELTA);
    assertEquals(0.69, registry.get("gatewai.co2.avoided.grams")
        .counter().count(), DELTA);
    assertEquals(1.0, registry.get("gatewai.cache.misses")
        .counter().count(), DELTA);
    assertEquals(1L, registry.get("gatewai.request.latency")
        .tags("model", "sonnet").timer().count());
  }

  @Test
  void countsCacheHits() {
    recorder.record(log("haiku", 10, true, GreenMetrics.ZERO));

    assertEquals(1.0, registry.get("gatewai.cache.hits").counter().count(), DELTA);
  }

  @Test
  void handlesNullModelAndMissingGreenMetrics() {
    recorder.record(new RequestLog(UUID.randomUUID(), Instant.now(), null,
        "hash", 0, 0, 0, 0L, "client", null, false));

    assertEquals(1.0, registry.get("gatewai.requests")
        .tags("model", "unknown", "cache_hit", "false").counter().count(), DELTA);
  }
}
