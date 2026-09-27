package io.github.yourimartin.gatewai.adapter.in.web.admin;

/**
 * Optional risk levels for a recalibration (v2 batch 3). Both null uses the
 * configured defaults.
 *
 * @param routingAlpha share of prompts whose correct route may fall outside the
 *                     prediction set
 * @param cacheAlpha   share of non-servable pairs that may be served anyway
 */
public record RecalibrateRequest(Double routingAlpha, Double cacheAlpha) {
}
