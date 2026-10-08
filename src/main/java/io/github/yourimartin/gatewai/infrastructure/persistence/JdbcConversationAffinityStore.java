package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.yourimartin.gatewai.domain.model.routing.ConversationAffinity;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@link ConversationAffinityStore} on the {@code conversation_affinity} table
 * (ADR 0015, v4 A.3).
 *
 * <p>Plain JDBC rather than JPA, because the one write that matters is an
 * upsert whose rule — never downgrade — has to hold when two nodes write the
 * same conversation at once. In SQL it is one statement and the row lock does
 * the arbitration; a JPA read-modify-write would need a lock of its own to say
 * the same thing.
 */
@Component
class JdbcConversationAffinityStore implements ConversationAffinityStore {

  private static final Logger LOG =
      LoggerFactory.getLogger(JdbcConversationAffinityStore.class);

  /**
   * When the offered row replaces the recorded one: the record has expired, or
   * the offered tier is strictly higher. A tier's rank is built from
   * {@link ModelTier}'s declaration order, so the database compares tiers the
   * way the domain does — generated from enum constants, never from input.
   */
  private static final String REPLACES = "(a.last_seen_at < ? OR "
      + rank("EXCLUDED.tier") + " > " + rank("a.tier") + ")";

  private static final String UPSERT = """
      INSERT INTO conversation_affinity AS a
          (fingerprint, model_id, tier, created_at, last_seen_at)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (fingerprint) DO UPDATE SET
          model_id = CASE WHEN {replaces} THEN EXCLUDED.model_id ELSE a.model_id END,
          tier = CASE WHEN {replaces} THEN EXCLUDED.tier ELSE a.tier END,
          created_at = CASE WHEN a.last_seen_at < ? THEN EXCLUDED.created_at
                            ELSE a.created_at END,
          last_seen_at = EXCLUDED.last_seen_at
      """.replace("{replaces}", REPLACES);

  private final JdbcTemplate jdbc;
  private final ConversationAffinityProperties properties;

  JdbcConversationAffinityStore(JdbcTemplate jdbc,
                                ConversationAffinityProperties properties) {
    this.jdbc = jdbc;
    this.properties = properties;
  }

  @Override
  public Optional<ConversationAffinity> find(String fingerprint) {
    List<ConversationAffinity> rows = jdbc.query("""
        SELECT fingerprint, model_id, tier, created_at, last_seen_at
          FROM conversation_affinity
         WHERE fingerprint = ? AND last_seen_at >= ?
        """, (rs, i) -> toAffinity(rs.getString(1), rs.getString(2), rs.getString(3),
            rs.getTimestamp(4), rs.getTimestamp(5)),
        fingerprint, Timestamp.from(cutoff()));
    return rows.stream().filter(Objects::nonNull).findFirst();
  }

  @Override
  public void record(String fingerprint, String modelId, ModelTier tier) {
    Timestamp now = Timestamp.from(Instant.now());
    Timestamp cutoff = Timestamp.from(cutoff());
    // The expiry test appears three times: model, tier, created_at.
    jdbc.update(UPSERT, fingerprint, modelId, tier.name(), now, now,
        cutoff, cutoff, cutoff);
  }

  @Override
  public int purgeLastSeenBefore(Instant cutoff) {
    return jdbc.update("DELETE FROM conversation_affinity WHERE last_seen_at < ?",
        Timestamp.from(cutoff));
  }

  private Instant cutoff() {
    return Instant.now().minus(properties.getConversationTtl());
  }

  /**
   * Null for a tier this version does not know — written by a newer node during
   * a rolling upgrade — which reads as no record rather than as a failure.
   */
  private static ConversationAffinity toAffinity(String fingerprint, String modelId,
                                                 String tier, Timestamp createdAt,
                                                 Timestamp lastSeenAt) {
    try {
      return new ConversationAffinity(fingerprint, modelId, ModelTier.valueOf(tier),
          createdAt.toInstant(), lastSeenAt.toInstant());
    } catch (IllegalArgumentException e) {
      LOG.warn("Ignoring a conversation record with unknown tier {}", tier);
      return null;
    }
  }

  private static String rank(String column) {
    return Arrays.stream(ModelTier.values())
        .map(tier -> "WHEN '" + tier.name() + "' THEN " + tier.ordinal())
        .collect(Collectors.joining(" ", "(CASE " + column + " ", " ELSE -1 END)"));
  }
}
