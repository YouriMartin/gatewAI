package io.github.yourimartin.gatewai;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.github.yourimartin.gatewai.domain.model.routing.ConversationAffinity;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;

/**
 * A {@link ConversationAffinityStore} in a map, with the port's never-downgrade
 * rule (v4 A.3) — what the router and cache tests need to follow a conversation
 * across turns without a database. Retention is the JDBC store's concern and is
 * tested there.
 */
public final class InMemoryConversationAffinityStore implements ConversationAffinityStore {

  private final Map<String, ConversationAffinity> records = new ConcurrentHashMap<>();

  @Override
  public Optional<ConversationAffinity> find(String fingerprint) {
    return Optional.ofNullable(records.get(fingerprint));
  }

  @Override
  public void record(String fingerprint, String modelId, ModelTier tier) {
    Instant now = Instant.now();
    records.merge(fingerprint, new ConversationAffinity(fingerprint, modelId, tier, now, now),
        (existing, offered) -> tier.isAbove(existing.tier())
            ? new ConversationAffinity(fingerprint, modelId, tier, existing.createdAt(), now)
            : new ConversationAffinity(fingerprint, existing.modelId(), existing.tier(),
                existing.createdAt(), now));
  }

  @Override
  public int purgeLastSeenBefore(Instant cutoff) {
    int before = records.size();
    records.values().removeIf(record -> record.lastSeenAt().isBefore(cutoff));
    return before - records.size();
  }

  /** Every record, keyed by fingerprint. */
  public Map<String, ConversationAffinity> records() {
    return Map.copyOf(records);
  }
}
