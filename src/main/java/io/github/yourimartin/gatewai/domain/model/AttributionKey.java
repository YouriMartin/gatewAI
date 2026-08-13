package io.github.yourimartin.gatewai.domain.model;

/**
 * What an attribution is only valid for (v2 batch 7).
 *
 * <p>The prompt hash and the embedding model are the obvious parts. The routing
 * config version is the one the plan did not name and the code needs: an
 * attribution decomposes the similarity to <b>the matched route's closest
 * example</b>, so editing a route — or its examples — changes what the numbers
 * are even about. Keyed on the prompt alone, a cached report would keep
 * explaining a decision the gateway no longer takes.
 *
 * @param promptHash           SHA-256 of the prompt, never the prompt
 * @param embeddingModel       the model whose vectors produced the similarities
 * @param routingConfigVersion the routing rules those similarities were measured
 *                             against
 */
public record AttributionKey(String promptHash, String embeddingModel,
                             String routingConfigVersion) {
}
