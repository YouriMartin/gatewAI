package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops conversation records past {@code gatewai.routing.conversation-ttl}
 * (ADR 0015, v4 A.3), on one node at a time.
 *
 * <p>The purge is housekeeping, not what enforces the retention: a lookup
 * already ignores a record older than the TTL, so a purge that runs late — or a
 * node that loses every lock race — changes the table's size, never a routing
 * decision. Failures are logged and swallowed, like the decision purge.
 */
@Component
class ConversationAffinityPurgeWorker {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConversationAffinityPurgeWorker.class);

  private final ConversationAffinityStore store;
  private final ConversationAffinityProperties properties;
  private final LeaderLock leaderLock;

  ConversationAffinityPurgeWorker(ConversationAffinityStore store,
                                  ConversationAffinityProperties properties,
                                  LeaderLock leaderLock) {
    this.store = store;
    this.properties = properties;
    this.leaderLock = leaderLock;
  }

  @Scheduled(fixedDelayString =
      "${gatewai.routing.conversation-purge-interval-ms:3600000}",
      initialDelayString = "${gatewai.routing.conversation-purge-interval-ms:3600000}")
  void purge() {
    try {
      leaderLock.runIfLeader(LeaderTask.CONVERSATION_AFFINITY_PURGE, this::purgeNow);
    } catch (RuntimeException e) {
      LOG.warn("Conversation purge failed: {}", e.toString());
    }
  }

  private void purgeNow() {
    Instant cutoff = Instant.now().minus(properties.getConversationTtl());
    int removed = store.purgeLastSeenBefore(cutoff);
    if (removed > 0) {
      LOG.info("Purged {} conversation record(s) last seen before {}", removed, cutoff);
    }
  }

  /** Enables scheduling for the purge, independently of the other workers. */
  @Configuration
  @EnableScheduling
  static class SchedulingConfig {
  }
}
