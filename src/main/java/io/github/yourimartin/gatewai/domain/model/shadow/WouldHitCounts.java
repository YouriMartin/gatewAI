package io.github.yourimartin.gatewai.domain.model.shadow;

/**
 * Aggregated would-hit assessments of one client under one judge model and
 * rubric, over a period (v4 D.2) — what the wrong-hit estimate is computed from.
 *
 * @param clientId        the shadow client
 * @param judgeModel      the judge, null for the would-hits nobody judged
 * @param rubricVersion   the rubric, null with {@code judgeModel}
 * @param wouldHits       assessments in the group
 * @param meanSimilarity  mean answer similarity over those that have one, null if none
 * @param beyondWindow    how many had an answer longer than the embedding window
 * @param yes             verdicts YES
 * @param no              verdicts NO
 * @param unsure          verdicts UNSURE
 * @param failed          sampled would-hits whose judge call failed
 */
public record WouldHitCounts(String clientId, String judgeModel, String rubricVersion,
                             long wouldHits, Double meanSimilarity, long beyondWindow,
                             long yes, long no, long unsure, long failed) {
}
