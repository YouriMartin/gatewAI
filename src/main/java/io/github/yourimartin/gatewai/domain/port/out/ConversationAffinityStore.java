package io.github.yourimartin.gatewai.domain.port.out;

import java.time.Instant;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.routing.ConversationAffinity;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

/**
 * Which model each conversation is held on (ADR 0015, v4 A.3) — shared by every
 * node, so a conversation started on one is sticky on the others.
 */
public interface ConversationAffinityStore {

  /** The live record for {@code fingerprint}; empty when absent or past retention. */
  Optional<ConversationAffinity> find(String fingerprint);

  /**
   * Records that the conversation runs on {@code modelId}, and marks it seen.
   *
   * <p><b>Never downgrades</b>, atomically: a record whose tier is already at or
   * above {@code tier} keeps its model and only has {@code lastSeenAt} moved, so
   * two nodes writing the same conversation at once leave the higher of the two.
   * A record past retention is replaced as if it were absent.
   */
  void record(String fingerprint, String modelId, ModelTier tier);

  /**
   * Moves the conversation to another model of the <b>same</b> tier, because a
   * failover made that model answer (v4 B.5, ADR 0017), and marks it seen.
   *
   * <p>Changes nothing unless the live record is on {@code tier}: a record that
   * another node has meanwhile moved up keeps its higher tier, and a missing or
   * expired one is not created here.
   */
  void reassign(String fingerprint, String modelId, ModelTier tier);

  /** Drops records last seen before {@code cutoff}; returns how many. */
  int purgeLastSeenBefore(Instant cutoff);
}
