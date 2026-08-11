package io.github.yourimartin.gatewai.eval;

/**
 * Where a recorded fixture came from (v2 batch 5).
 *
 * <p>Same discipline as a persisted routing decision: numbers replayed without
 * knowing which model and which configuration produced them are numbers that
 * quietly describe something else. Every field here is a reason for the harness
 * to refuse to score stale fixtures.
 *
 * @param embeddingModel       the model that produced the vectors
 * @param dimensions           its vector width, asserted on replay
 * @param recordedAt           ISO-8601 instant of the recording run
 * @param datasetDigest        fingerprint of the dataset files at recording time
 * @param routingConfigVersion {@code RoutingConfigVersion} of the routing rules
 *                             the recording ran against
 */
record FixtureProvenance(String embeddingModel, int dimensions, String recordedAt,
                         String datasetDigest, String routingConfigVersion) {
}
