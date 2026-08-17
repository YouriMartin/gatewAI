package io.github.yourimartin.gatewai.domain.port.out;

import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.StoredRoutingConfig;

/**
 * The cluster-wide home of the routing rules (v3 lot B.1).
 *
 * <p>Distinct from {@link RoutingConfigPort}, which is the <em>node's</em> view:
 * the port is what the classifier reads on every call and must stay a memory
 * read, this is the shared source of truth it is a cache over.
 *
 * <p>The two writers are column-scoped on purpose. A node's cached copy can be
 * up to one poll interval stale, so writing the whole row from that copy would
 * let a config edit on node A silently revert a band edit made on node B a
 * second earlier. Each method touches only what its caller actually changed.
 */
public interface RoutingConfigStore {

  /** The stored rules, or empty when no node has written them yet. */
  Optional<StoredRoutingConfig> load();

  /**
   * Writes the initial rules if nothing is stored yet, then returns whatever is
   * stored — which may be another node's. Two nodes cold-starting together both
   * end up on the winner's configuration rather than on their own defaults, and
   * neither fails.
   */
  StoredRoutingConfig seedIfAbsent(RoutingConfig config,
                                   double cascadeMarginBand);

  /** Replaces the rules, leaving the cascade band as stored. */
  StoredRoutingConfig saveConfig(RoutingConfig config);

  /** Replaces the cascade band, leaving the rules as stored. */
  StoredRoutingConfig saveCascadeMarginBand(double cascadeMarginBand);
}
