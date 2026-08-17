package io.github.yourimartin.gatewai.domain.model;

import java.time.Instant;

/**
 * The routing rules as they are stored for the whole cluster, with the revision
 * that tells a node whether it has already seen them (v3 lot B.1).
 *
 * <p>{@code revision} is a propagation signal, not an identity: it is bumped on
 * every write, even one that stores identical values, so a node can decide
 * whether to re-read with a {@code long} comparison instead of a payload
 * comparison. The identity of a set of rules stays
 * {@link RoutingConfigVersion}, which is content-addressed and is what a stored
 * decision carries.
 *
 * <p>{@code cascadeMarginBand} rides along because it is edited on the same
 * admin endpoint, but it is deliberately outside {@link RoutingConfig} — see
 * {@code RoutingConfigPort#cascadeMarginBand()}.
 *
 * @param revision           monotonic write counter, cluster-wide
 * @param config             the rules in force
 * @param cascadeMarginBand  the cascade's ambiguity band, versioned separately
 * @param updatedAt          when the row was last written
 */
public record StoredRoutingConfig(
    long revision,
    RoutingConfig config,
    double cascadeMarginBand,
    Instant updatedAt
) {
}
