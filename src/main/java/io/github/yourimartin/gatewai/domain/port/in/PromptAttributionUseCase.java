package io.github.yourimartin.gatewai.domain.port.in;

import io.github.yourimartin.gatewai.domain.model.AttributionReport;

/**
 * Explains which parts of a prompt drove its routing decision (v2 batch 7).
 *
 * <p><b>On demand only.</b> Attribution costs one embedding call per segment
 * plus one, against the same local model that serves requests, so it is never
 * computed while routing: the router decides, and someone later asks why.
 *
 * <p>Takes the prompt rather than a decision id because the answer is
 * recomputed, not stored — no plaintext prompt is persisted anywhere, so a past
 * decision cannot be re-embedded from its row. Batch 9's explain endpoint pairs
 * the two: the stored decision for what happened, this for what carried it.
 */
public interface PromptAttributionUseCase {

  AttributionReport attribute(String prompt);
}
