package io.github.yourimartin.gatewai.infrastructure.metrics;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughStreamOutcome;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.springframework.stereotype.Component;

/**
 * Records per-request green/usage metrics into Micrometer (Phase 6.1). Exported
 * at {@code /actuator/prometheus} as {@code gatewai_*} series, on top of Spring
 * AI's native model observations.
 */
@Component
class MicrometerMetricsRecorder implements MetricsRecorder {

  private final MeterRegistry registry;

  MicrometerMetricsRecorder(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void record(RequestLog log) {
    String model = log.model() == null ? "unknown" : log.model();
    Tags modelTag = Tags.of("model", model);

    registry.counter("gatewai.requests",
        modelTag.and("cache_hit", String.valueOf(log.cacheHit()))).increment();
    registry.counter("gatewai.tokens", modelTag).increment(log.totalTokens());
    registry.counter(
        log.cacheHit() ? "gatewai.cache.hits" : "gatewai.cache.misses")
        .increment();
    registry.timer("gatewai.request.latency", modelTag)
        .record(log.latencyMs(), TimeUnit.MILLISECONDS);

    GreenMetrics green = log.green();
    if (green != null) {
      // Named for the currency until v4 B.6 (gatewai.cost.eur), whatever the
      // prices were in. A series per currency and basis now, never summed across.
      String currency = green.currency() == null ? "unknown" : green.currency();
      registry.counter("gatewai.cost", modelTag
              .and("currency", currency)
              .and("pricing_basis", green.pricingBasis().name().toLowerCase(Locale.ROOT)))
          .increment(green.cost());
      registry.counter("gatewai.cost.avoided", "currency", currency)
          .increment(green.costAvoided());
      registry.counter("gatewai.energy.kwh", modelTag)
          .increment(green.energyKwh());
      registry.counter("gatewai.co2.grams", modelTag)
          .increment(green.gramsCo2());
      registry.counter("gatewai.co2.avoided.grams")
          .increment(green.gramsCo2Avoided());
    }
  }

  /**
   * {@code gatewai.passthrough.streams}, tagged by model and outcome (v4 B.4). The
   * {@code client_disconnected} share is the part of streamed pass-through traffic
   * that the green report cannot see.
   */
  @Override
  public void recordPassThroughStream(String model, PassThroughStreamOutcome outcome) {
    registry.counter("gatewai.passthrough.streams",
        "model", model == null ? "unknown" : model,
        "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
  }
}
