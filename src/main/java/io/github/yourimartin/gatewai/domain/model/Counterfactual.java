package io.github.yourimartin.gatewai.domain.model;

/**
 * A routing outcome the request did <b>not</b> get, and how close it came to
 * getting it (v2 batch 8).
 *
 * <p>Reads as "this request would have gone to {@code tier} had it looked more
 * like {@code nearestUtterance}" — the counterfactual of a semantic decision.
 * Where an occlusion attribution decomposes the winning similarity
 * ({@link AttributionReport}), this compares it to the ones that lost: the same
 * ranking, read the other way round.
 *
 * @param route            the route that would have won
 * @param tier             the tier that route maps to — the alternative
 *                         <em>outcome</em>, which is what makes it worth showing
 * @param nearestUtterance that route's example closest to the request. Always a
 *                         configured example, never anything the client sent
 * @param similarity       how close that example came
 * @param gap              {@code chosen similarity − this similarity}, never
 *                         negative since the chosen route won. Small means the
 *                         decision nearly went the other way — the number worth
 *                         reading before trusting the tier. Batch 9's explain
 *                         endpoint renders it as {@code delta}
 * @param rank             1-based position among the alternatives, closest first
 */
public record Counterfactual(String route, ModelTier tier,
                             String nearestUtterance, double similarity,
                             double gap, int rank) {
}
