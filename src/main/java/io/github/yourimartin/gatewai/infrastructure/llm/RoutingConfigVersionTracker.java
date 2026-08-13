package io.github.yourimartin.gatewai.infrastructure.llm;

import java.time.Instant;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.RoutingConfigVersion;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tracks the version of the live routing rules (v2 batch 2).
 *
 * <p>The rules are editable in production through {@code PUT /v1/admin/routing}
 * and the dashboard, so the version is recomputed from the current
 * configuration rather than captured at startup. Recomputation is skipped while
 * the configuration is unchanged (record equality on the snapshot, the same
 * trick the route index uses), so the hot path pays a comparison, not a digest.
 *
 * <p>Every change is logged with its timestamp and counted as
 * {@code gatewai.routing.config.changes} (v2 batch 6). That is what lets a shift
 * in the tier mix be attributed to an edit — or, more interestingly, be shown
 * <em>not</em> to have one, which means the incoming traffic drifted rather than
 * the rules. A log line alone cannot be graphed next to the mix; a counter can.
 */
@Component
class RoutingConfigVersionTracker {

  private static final Logger LOG =
      LoggerFactory.getLogger(RoutingConfigVersionTracker.class);

  private final RoutingConfigPort routingConfig;
  private final Counter changes;

  private volatile Snapshot snapshot;

  RoutingConfigVersionTracker(RoutingConfigPort routingConfig,
                              MeterRegistry registry) {
    this.routingConfig = routingConfig;
    // Registered up front so the drift panel has a series to draw before the
    // first edit, rather than a gap that reads like missing instrumentation.
    this.changes = Counter.builder("gatewai.routing.config.changes")
        .description("Edits to the live routing rules")
        .register(registry);
  }

  /** The version of the rules in force right now. */
  String current() {
    RoutingConfig config = routingConfig.get();
    Snapshot current = snapshot;
    if (current != null && current.config().equals(config)) {
      return current.version();
    }

    String version = RoutingConfigVersion.of(config);
    if (current != null && !current.version().equals(version)) {
      LOG.info("Routing config changed at {}: version {} -> {}",
          Instant.now(), current.version(), version);
      changes.increment();
    }
    snapshot = new Snapshot(config, version);
    return version;
  }

  private record Snapshot(RoutingConfig config, String version) {
  }
}
