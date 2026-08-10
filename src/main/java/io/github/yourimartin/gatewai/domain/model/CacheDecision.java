package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One semantic-cache decision, as taken (v2 batch 2).
 *
 * <p>The cache sits <b>upstream</b> of the router, so on a hit no routing
 * decision exists at all and the trace used to be blind exactly where the user
 * risk is highest: a routing mistake costs money, a cache false positive answers
 * a different question.
 *
 * @param id                      surrogate id
 * @param correlationId           id of the request being served
 * @param createdAt               when the decision was taken
 * @param promptHash              SHA-256 of the user text, never the text
 * @param outcome                 what the cache did
 * @param similarityScore         best candidate's similarity, 0 when none
 * @param runnerUpScore           second-best candidate's similarity, null when
 *                                there was no second candidate. The implicit
 *                                margin: 0.93 against 0.92 is a coin flip, 0.93
 *                                against 0.41 is not
 * @param threshold               the acceptance threshold in force
 * @param matchedEntryId          the served entry, null unless {@code HIT}
 * @param matchedEntryAgeSeconds  how old that entry was when served
 * @param originCorrelationId     correlation id of the request that <b>wrote</b>
 *                                the served entry — the link that makes a hit
 *                                auditable back to the routing decision that
 *                                produced the answer
 * @param embeddingModel          which model produced the vectors
 */
public record CacheDecision(
    UUID id,
    String correlationId,
    Instant createdAt,
    String promptHash,
    CacheOutcome outcome,
    double similarityScore,
    Double runnerUpScore,
    double threshold,
    String matchedEntryId,
    Long matchedEntryAgeSeconds,
    String originCorrelationId,
    String embeddingModel
) {
}
