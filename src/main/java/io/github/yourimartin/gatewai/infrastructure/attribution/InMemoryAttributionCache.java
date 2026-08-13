package io.github.yourimartin.gatewai.infrastructure.attribution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.AttributionKey;
import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.port.out.AttributionCache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded LRU cache of attribution reports (v2 batch 7).
 *
 * <p>Bounded is the whole design. Attributions are keyed by prompt, and prompts
 * are user input: an unbounded map would grow with traffic and would be a
 * plausible-looking memory leak. The eldest entry goes when the cap is reached,
 * which for a cache read by humans asking "why this one?" is exactly right —
 * the recently explained is what gets explained again.
 *
 * <p>In memory, so it dies with the process and is per-instance. That is
 * acceptable for something whose miss costs a recomputation rather than a wrong
 * answer; the durable version of this problem is the decision tables.
 */
@Component
class InMemoryAttributionCache implements AttributionCache {

  private final Map<AttributionKey, AttributionReport> reports;

  InMemoryAttributionCache(
      @Value("${gatewai.attribution.cache-size:500}") int maxEntries) {
    this.reports =
        Collections.synchronizedMap(new LruMap(Math.max(1, maxEntries)));
  }

  @Override
  public Optional<AttributionReport> get(AttributionKey key) {
    return Optional.ofNullable(reports.get(key));
  }

  @Override
  public void put(AttributionKey key, AttributionReport report) {
    reports.put(key, report);
  }

  /** Test seam: how many reports are held right now. */
  int size() {
    return reports.size();
  }

  /**
   * Access-ordered map that drops its eldest entry past {@code capacity} — the
   * JDK's own way of writing an LRU, and a named class rather than an anonymous
   * one so it captures nothing but the bound.
   */
  private static final class LruMap
      extends LinkedHashMap<AttributionKey, AttributionReport> {

    private static final long serialVersionUID = 1L;

    private final int capacity;

    private LruMap(int capacity) {
      super(16, 0.75f, true);
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(
        Map.Entry<AttributionKey, AttributionReport> eldest) {
      return size() > capacity;
    }
  }
}
