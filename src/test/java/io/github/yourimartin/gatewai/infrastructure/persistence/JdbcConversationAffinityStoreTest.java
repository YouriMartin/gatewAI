package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.routing.ConversationAffinity;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The never-downgrade upsert and the retention, on real SQL (ADR 0015, v4 A.3):
 * the rule has to hold in the statement itself, because two nodes may write
 * the same conversation at once.
 *
 * <p>Needs Postgres, so {@code @Tag("integration")} — run with
 * {@code ./mvnw -Pit test}.
 */
@Tag("integration")
@SpringBootTest(properties = {
    "spring.profiles.active=mock",
    "gatewai.routing.conversation-ttl=1h"
})
class JdbcConversationAffinityStoreTest {

  @Autowired
  private ConversationAffinityStore store;

  @Autowired
  private JdbcTemplate jdbc;

  private final String fingerprint = UUID.randomUUID().toString().replace("-", "");

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM conversation_affinity WHERE fingerprint = ?", fingerprint);
  }

  @Test
  void aHigherTierReplacesTheModelAndALowerOneNeverDoes() {
    store.record(fingerprint, "local-1", ModelTier.LOCAL);
    store.record(fingerprint, "premium-1", ModelTier.CLOUD_PREMIUM);
    store.record(fingerprint, "entry-1", ModelTier.CLOUD_ENTRY);
    store.record(fingerprint, "premium-2", ModelTier.CLOUD_PREMIUM);

    ConversationAffinity recorded = store.find(fingerprint).orElseThrow();
    assertEquals("premium-1", recorded.modelId(),
        "an equal tier must keep the recorded model, a lower one must not touch it");
    assertEquals(ModelTier.CLOUD_PREMIUM, recorded.tier());
  }

  @Test
  void everyWriteMovesLastSeenButNotCreatedAt() {
    store.record(fingerprint, "premium-1", ModelTier.CLOUD_PREMIUM);
    ConversationAffinity first = store.find(fingerprint).orElseThrow();

    store.record(fingerprint, "local-1", ModelTier.LOCAL);
    ConversationAffinity second = store.find(fingerprint).orElseThrow();

    assertEquals(first.createdAt(), second.createdAt());
    assertTrue(!second.lastSeenAt().isBefore(first.lastSeenAt()));
  }

  @Test
  void aRecordPastItsTtlIsInvisibleAndIsReplacedAsIfAbsent() {
    store.record(fingerprint, "premium-1", ModelTier.CLOUD_PREMIUM);
    age(Duration.ofHours(2));

    assertTrue(store.find(fingerprint).isEmpty(), "an expired record was still read");

    store.record(fingerprint, "local-1", ModelTier.LOCAL);
    assertEquals("local-1", store.find(fingerprint).orElseThrow().modelId(),
        "an expired record still set the floor");
  }

  @Test
  void thePurgeDropsOnlyWhatWasLastSeenBeforeTheCutoff() {
    store.record(fingerprint, "premium-1", ModelTier.CLOUD_PREMIUM);
    age(Duration.ofHours(2));

    assertEquals(0, countAfter(Instant.now().minus(Duration.ofHours(3))));
    assertTrue(countAfter(Instant.now().minus(Duration.ofHours(1))) >= 1);
    assertEquals(0, jdbc.queryForObject(
        "SELECT count(*) FROM conversation_affinity WHERE fingerprint = ?",
        Integer.class, fingerprint));
  }

  private int countAfter(Instant cutoff) {
    return store.purgeLastSeenBefore(cutoff);
  }

  private void age(Duration by) {
    jdbc.update("UPDATE conversation_affinity SET last_seen_at = ? WHERE fingerprint = ?",
        Timestamp.from(Instant.now().minus(by)), fingerprint);
  }
}
