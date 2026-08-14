package io.github.yourimartin.gatewai.domain.model;

/**
 * The whole answer to "why did this request go there?" (v2 batch 9).
 *
 * <p>Three kinds of thing, deliberately kept apart rather than blended into one
 * narrative:
 *
 * <ul>
 *   <li><b>what happened</b> — {@code decision}, read from the trace, never
 *       recomputed;</li>
 *   <li><b>what carried it</b> — {@code attribution} (batch 7) and
 *       {@code counterfactuals} (batch 8), recomputed now, from the prompt;</li>
 *   <li><b>what it is all relative to</b> — {@code provenance}, which is what
 *       lets a reader notice the first two are describing different worlds.</li>
 * </ul>
 *
 * <p>They come apart precisely where it matters: no plaintext prompt is stored,
 * so explaining a <em>past</em> decision yields a full {@code decision} and
 * reports whose status is {@link AttributionStatus#PROMPT_UNAVAILABLE}, while
 * explaining a <em>prompt</em> yields the reverse — the analysis, with no
 * decision, because none was taken.
 *
 * @param decision        the persisted trace, null for an on-the-fly prompt
 * @param attribution     which segments carried the match, never null
 * @param counterfactuals which outcomes were missed, never null
 * @param provenance      never null
 */
public record DecisionExplanation(TracedDecision decision,
                                  AttributionReport attribution,
                                  CounterfactualReport counterfactuals,
                                  ExplanationProvenance provenance) {
}
