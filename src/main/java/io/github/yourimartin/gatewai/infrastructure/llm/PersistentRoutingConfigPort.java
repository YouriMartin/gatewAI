package io.github.yourimartin.gatewai.infrastructure.llm;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.StoredRoutingConfig;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigStore;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The routing rules as this node sees them: a cache over the shared
 * {@link RoutingConfigStore} (v3 lot B.1).
 *
 * <p>Before this, the rules lived only in {@link ClassifierProperties}. A
 * {@code PUT /v1/admin/routing} on one replica therefore left every other
 * replica routing on the old rules indefinitely, each stamping its own
 * {@code routing_config_version} onto its decisions — so the same prompt could
 * be explained two ways, with both explanations internally consistent and one of
 * them wrong. Reads stay a memory access, because the classifier does one per
 * request; only writes and the poll touch the database.
 *
 * <p>Propagation is polling, not {@code LISTEN}/{@code NOTIFY}: the divergence
 * window is then a bounded, documented number
 * ({@code gatewai.routing.config-sync-interval-ms}, 5 s by default) instead of a
 * connection that can quietly stop delivering. One small single-row
 * {@code SELECT} per node per interval is not a cost worth optimising against
 * that.
 *
 * <p>Sits in front of {@link ClassifierRoutingConfigAdapter} rather than
 * replacing it: that class stays the node-local view the classifier reads, and
 * keeping it a separate {@link RoutingConfigPort} implementation is what makes
 * this one testable without a database — and what a single-node build could fall
 * back to.
 */
@Component
@Primary
class PersistentRoutingConfigPort implements RoutingConfigPort {

  private static final Logger LOG =
      LoggerFactory.getLogger(PersistentRoutingConfigPort.class);

  /** No revision seen yet; the store's first revision is 1. */
  private static final long NONE = 0;

  private final ClassifierRoutingConfigAdapter local;
  private final RoutingConfigStore store;

  private volatile long appliedRevision = NONE;

  PersistentRoutingConfigPort(ClassifierRoutingConfigAdapter local,
                              RoutingConfigStore store) {
    this.local = local;
    this.store = store;
  }

  /**
   * Adopts the stored rules before the node serves anything, seeding them from
   * {@code application.properties} on a first start.
   *
   * <p>{@code @PostConstruct} rather than an {@code ApplicationRunner}: runners
   * fire after the web server is accepting connections, which would leave a
   * window where requests route on defaults the cluster has already replaced.
   * Failures propagate — a gateway that cannot read the rules must not start and
   * guess them, and it already cannot start without this database.
   */
  @PostConstruct
  void adoptStoredConfig() {
    StoredRoutingConfig stored = store.load()
        .orElseGet(() -> store.seedIfAbsent(
            local.get(), local.cascadeMarginBand()));
    LOG.info("Routing config loaded from the shared store at revision {}",
        stored.revision());
    apply(stored);
  }

  /**
   * Picks up an edit made on another node.
   *
   * <p>Failures are logged and swallowed: the last-known rules are a better
   * answer than no rules, and a database blip must not reset a node to its
   * defaults. A prolonged outage shows up as a node that stops converging, which
   * is why the revision is logged when it moves.
   */
  @Scheduled(
      fixedDelayString = "${gatewai.routing.config-sync-interval-ms:5000}",
      initialDelayString = "${gatewai.routing.config-sync-interval-ms:5000}")
  void sync() {
    try {
      store.load()
          .filter(stored -> stored.revision() != appliedRevision)
          .ifPresent(this::apply);
    } catch (RuntimeException e) {
      LOG.warn("Could not read the shared routing config, keeping revision {}: {}",
          appliedRevision, e.toString());
    }
  }

  @Override
  public RoutingConfig get() {
    return local.get();
  }

  /**
   * Persists first, applies second. A write that fails therefore fails the
   * request instead of taking effect on this node alone — which is the failure
   * mode this batch exists to remove.
   */
  @Override
  public void update(RoutingConfig config) {
    apply(store.saveConfig(config));
  }

  @Override
  public double cascadeMarginBand() {
    return local.cascadeMarginBand();
  }

  @Override
  public void updateCascadeMarginBand(double band) {
    apply(store.saveCascadeMarginBand(band));
  }

  /**
   * Copies the stored rules into the node-local view, touching only what
   * actually differs.
   *
   * <p>The comparison is not an optimisation, it is what keeps the derived state
   * honest: the semantic route indexes rebuild when the routes they hold stop
   * being equal, and {@code RoutingConfigVersionTracker} counts a change when
   * the version moves. Re-applying an equal config would be invisible to both,
   * but it would also make the logs claim edits nobody made.
   */
  private void apply(StoredRoutingConfig stored) {
    if (!stored.config().equals(local.get())) {
      local.update(stored.config());
      LOG.info("Routing rules applied from revision {} (edited at {})",
          stored.revision(), stored.updatedAt());
    }
    // Exact comparison, deliberately: this asks whether the stored double is the
    // one already in force, not whether two computed values are close.
    if (Double.compare(stored.cascadeMarginBand(),
        local.cascadeMarginBand()) != 0) {
      LOG.info("Cascade margin band applied from revision {}: {} -> {}",
          stored.revision(), local.cascadeMarginBand(),
          stored.cascadeMarginBand());
      local.updateCascadeMarginBand(stored.cascadeMarginBand());
    }
    appliedRevision = stored.revision();
  }

  /**
   * Enables scheduling for the config poll, on its own so it runs whether or not
   * decision recording and carbon-aware dispatch — the other two schedulers —
   * are switched on.
   */
  @Configuration
  @EnableScheduling
  static class SchedulingConfig {
  }
}
