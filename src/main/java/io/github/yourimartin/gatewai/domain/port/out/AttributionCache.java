package io.github.yourimartin.gatewai.domain.port.out;

import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.AttributionKey;
import io.github.yourimartin.gatewai.domain.model.AttributionReport;

/**
 * Remembers computed attributions (v2 batch 7).
 *
 * <p>A port of its own, and deliberately <b>not</b> the per-request embedding
 * memo from batch 0.2: that one exists for the length of a request and is bound
 * to a scoped value, while this outlives requests and must therefore be bounded
 * — an unbounded map of reports keyed by prompt is a memory leak with a
 * plausible-sounding name.
 *
 * <p>Reports are cached under an {@link AttributionKey}, which pins the prompt,
 * the embedding model <b>and</b> the routing rules, so an edit to a route
 * invalidates what it would otherwise keep explaining.
 */
public interface AttributionCache {

  Optional<AttributionReport> get(AttributionKey key);

  void put(AttributionKey key, AttributionReport report);
}
