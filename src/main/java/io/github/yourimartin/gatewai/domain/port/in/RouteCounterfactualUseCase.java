package io.github.yourimartin.gatewai.domain.port.in;

import io.github.yourimartin.gatewai.domain.model.CounterfactualReport;

/**
 * Says where a prompt would have gone instead, and how close it came
 * (v2 batch 8).
 *
 * <p>Sibling of {@link PromptAttributionUseCase}, and the other half of the same
 * question: attribution says which words carried the match, this says which
 * outcomes the request missed and by how much. A gap of 0.01 to a premium route
 * is the reader's cue that the tier is not a robust fact about this request —
 * the kind of thing no single-number decision log ever shows.
 *
 * <p>Takes the prompt for the same reason attribution does: no plaintext prompt
 * is persisted, so a past decision cannot be re-ranked from its row.
 *
 * <p><b>One embedding call</b>, against an index of route examples that is
 * already built — cheap enough that, unlike attribution, it caches nothing.
 */
public interface RouteCounterfactualUseCase {

  CounterfactualReport explore(String prompt);
}
