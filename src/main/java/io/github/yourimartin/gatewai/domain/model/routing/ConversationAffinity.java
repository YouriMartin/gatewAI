package io.github.yourimartin.gatewai.domain.model.routing;

import java.time.Instant;

/**
 * The model a conversation is held on (ADR 0015, v4 A.3).
 *
 * <p>Hashes and identifiers only: no prompt or answer text is ever stored.
 *
 * @param fingerprint the {@link ConversationOpening#fingerprint() opening}'s hash
 * @param modelId     the registry model id the conversation runs on
 * @param tier        its tier — the floor no later turn goes below
 * @param createdAt   when the record was first written
 * @param lastSeenAt  the last turn that read or wrote it; the retention clock
 */
public record ConversationAffinity(String fingerprint, String modelId, ModelTier tier,
                                   Instant createdAt, Instant lastSeenAt) {
}
