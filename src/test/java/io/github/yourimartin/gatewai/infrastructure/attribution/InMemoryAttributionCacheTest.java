package io.github.yourimartin.gatewai.infrastructure.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.AttributionKey;
import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.model.AttributionStatus;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.SegmentAttribution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryAttributionCacheTest {

  @Test
  @DisplayName("a stored report comes back")
  void roundTrip() {
    InMemoryAttributionCache cache = new InMemoryAttributionCache(10);
    AttributionKey key = key("hash-1");

    cache.put(key, report());

    assertEquals(AttributionStatus.COMPUTED, cache.get(key).orElseThrow().status());
    assertTrue(cache.get(key("hash-2")).isEmpty());
  }

  @Test
  @DisplayName("the cache is bounded: prompts are user input")
  void evictsBeyondTheCap() {
    InMemoryAttributionCache cache = new InMemoryAttributionCache(2);

    cache.put(key("hash-1"), report());
    cache.put(key("hash-2"), report());
    cache.put(key("hash-3"), report());

    assertEquals(2, cache.size());
    assertTrue(cache.get(key("hash-1")).isEmpty(), "the eldest goes first");
    assertTrue(cache.get(key("hash-3")).isPresent());
  }

  @Test
  @DisplayName("reading an entry keeps it: recently explained is explained again")
  void evictionIsLeastRecentlyUsed() {
    InMemoryAttributionCache cache = new InMemoryAttributionCache(2);
    cache.put(key("hash-1"), report());
    cache.put(key("hash-2"), report());

    cache.get(key("hash-1"));
    cache.put(key("hash-3"), report());

    assertTrue(cache.get(key("hash-1")).isPresent());
    assertTrue(cache.get(key("hash-2")).isEmpty());
  }

  @Test
  @DisplayName("the same prompt under other rules is another entry")
  void keyCoversTheModelAndTheRoutingRules() {
    InMemoryAttributionCache cache = new InMemoryAttributionCache(10);
    cache.put(new AttributionKey("hash", "nomic-embed-text", "cfg-1"), report());

    assertTrue(cache.get(
        new AttributionKey("hash", "nomic-embed-text", "cfg-2")).isEmpty());
    assertTrue(cache.get(
        new AttributionKey("hash", "other-model", "cfg-1")).isEmpty());
  }

  @Test
  @DisplayName("a nonsensical cap still yields a usable cache")
  void capIsNeverBelowOne() {
    InMemoryAttributionCache cache = new InMemoryAttributionCache(0);

    cache.put(key("hash-1"), report());

    assertFalse(cache.get(key("hash-1")).isEmpty());
  }

  private static AttributionKey key(String promptHash) {
    return new AttributionKey(promptHash, "nomic-embed-text", "cfg-1");
  }

  private static AttributionReport report() {
    return new AttributionReport(AttributionStatus.COMPUTED, "code-and-analysis",
        ModelTier.CLOUD_PREMIUM, "Refactor this Java service", 0.82,
        List.of(new SegmentAttribution("Refactor this", 0.30, 1.0, 1)),
        "nomic-embed-text", "cfg-1");
  }
}
