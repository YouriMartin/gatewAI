package io.github.yourimartin.gatewai.infrastructure.metrics;

import java.util.List;
import java.util.Locale;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CascadeLevel;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Publishes routing and cache decisions to Micrometer (v2 batch 6).
 *
 * <p>Extends the stack already in place rather than adding a parallel one, and
 * registers <b>dotted</b> names (D8) — {@code gatewai.routing.decisions} is
 * scraped as {@code gatewai_routing_decisions_total}.
 *
 * <p>Two rules govern what is a tag here. Tags must be <b>bounded</b>: tiers,
 * reasons, strategies and levels are enums, so the series count is the product
 * of small numbers and stays constant over a process's life. And continuous
 * quantities — the routing margin, the cache similarity — are
 * {@link DistributionSummary}s rather than tags, because a similarity of 0.9431
 * as a label would mint one series per request.
 *
 * <p>The prompt, the model id and the correlation id are deliberately
 * <b>never</b> tags: the first two would leak content and cardinality, the third
 * is what the decision tables are for. Metrics answer "how often", the rows
 * answer "why this one".
 */
@Component
class MicrometerDecisionMetricsRecorder implements DecisionMetricsRecorder {

  private static final Logger LOG =
      LoggerFactory.getLogger(MicrometerDecisionMetricsRecorder.class);

  /** Tag value for "this decision had no such field", never a missing tag. */
  private static final String NONE = "none";

  private final MeterRegistry registry;

  MicrometerDecisionMetricsRecorder(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void record(RoutingDecision decision) {
    try {
      registry.counter("gatewai.routing.decisions",
          "tier", lower(decision.chosenTier()),
          "reason", lower(decision.decisionReason()),
          "strategy", lower(decision.strategy())).increment();

      escalation(decision.escalatedTo());
      margin(decision);
      predictionSet(decision.conformalSet());
    } catch (RuntimeException e) {
      failed("routing", e);
    }
  }

  @Override
  public void record(CacheDecision decision) {
    try {
      registry.counter("gatewai.cache.decisions",
          "outcome", lower(decision.outcome()),
          "conformal_status", lower(decision.conformalStatus())).increment();

      // The similarity of a bypass is 0 by convention, not by measurement:
      // recording it would drag the distribution toward a number nothing scored.
      if (decision.similarityScore() > 0) {
        summary("gatewai.cache.similarity", Tags.empty(),
            "Similarity of the best cache candidate")
            .record(decision.similarityScore());
      }
    } catch (RuntimeException e) {
      failed("cache", e);
    }
  }

  /**
   * How far the cascade went, counted for <b>every</b> level and not only for
   * escalations — the escalation rate is a ratio, and a numerator without its
   * denominator is not one. Null for every other strategy, which is what keeps
   * the ratio over cascade traffic only.
   */
  private void escalation(CascadeLevel level) {
    if (level == null) {
      return;
    }
    registry.counter("gatewai.cascade.escalations",
        "to_level", lower(level)).increment();
  }

  /**
   * The winning route's lead over the runner-up, per tier. A mix that stays put
   * while the margins collapse is the shape of input drift: same decisions,
   * much less confidence behind them.
   */
  private void margin(RoutingDecision decision) {
    ClassificationJustification.routeScores(decision.justification())
        .ifPresent(scores -> summary("gatewai.routing.margin",
            Tags.of("tier", lower(decision.chosenTier())),
            "Top route's similarity lead over the runner-up")
            .record(scores.margin()));
  }

  /**
   * The size of the conformal prediction set (v2 batch 3), which batch 4 turned
   * into the cascade's gate. Null means no calibration was in force, which is
   * not a set of size zero — recording it as one would invent a measurement.
   */
  private void predictionSet(List<ModelTier> set) {
    if (set == null) {
      return;
    }
    summary("gatewai.conformal.set.size", Tags.of("target", "routing"),
        "Number of tiers inside the conformal prediction set")
        .record(set.size());
  }

  private DistributionSummary summary(String name, Tags tags,
                                      String description) {
    return DistributionSummary.builder(name)
        .tags(tags)
        .description(description)
        .publishPercentiles(0.5, 0.95)
        .register(registry);
  }

  /**
   * Never rethrows: a decision that cannot be counted must still be taken. The
   * failure is itself counted, beside the one that guards decision persistence,
   * so "the graphs went quiet" and "nothing happened" stay distinguishable.
   */
  private void failed(String kind, RuntimeException e) {
    try {
      registry.counter("gatewai.decisions.metric.failures", "kind", kind)
          .increment();
    } catch (RuntimeException nested) {
      // The registry itself is the thing that broke. The log line below is then
      // the only signal left, and it is still better than an exception thrown
      // into a request that was otherwise served correctly.
      LOG.debug("Could not count the metric failure either: {}",
          nested.toString());
    }
    LOG.warn("Could not record {} decision metrics: {}", kind, e.toString());
  }

  private static String lower(Enum<?> value) {
    return value == null ? NONE : value.name().toLowerCase(Locale.ROOT);
  }
}
