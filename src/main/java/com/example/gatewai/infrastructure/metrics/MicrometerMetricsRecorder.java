package com.example.gatewai.infrastructure.metrics;

import java.util.concurrent.TimeUnit;

import com.example.gatewai.domain.model.GreenMetrics;
import com.example.gatewai.domain.model.RequestLog;
import com.example.gatewai.domain.port.out.MetricsRecorder;

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
      registry.counter("gatewai.cost.eur", modelTag)
          .increment(green.costEur());
      registry.counter("gatewai.cost.avoided.eur")
          .increment(green.costAvoidedEur());
      registry.counter("gatewai.energy.kwh", modelTag)
          .increment(green.energyKwh());
      registry.counter("gatewai.co2.grams", modelTag)
          .increment(green.gramsCo2());
      registry.counter("gatewai.co2.avoided.grams")
          .increment(green.gramsCo2Avoided());
    }
  }
}
