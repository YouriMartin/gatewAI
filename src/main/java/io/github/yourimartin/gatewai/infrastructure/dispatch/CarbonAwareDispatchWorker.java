package io.github.yourimartin.gatewai.infrastructure.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.port.in.DispatchDeferredJobsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled worker (Phase 4.4): on each tick it reads the current carbon
 * intensity of every candidate zone and hands the queued jobs to the dispatch
 * use case, which runs them at the greenest zone's intensity.
 *
 * <p>Only active when {@code gatewai.dispatch.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "gatewai.dispatch", name = "enabled",
    havingValue = "true")
class CarbonAwareDispatchWorker {

  private final DispatchDeferredJobsUseCase dispatchUseCase;
  private final CarbonIntensityProvider carbonIntensityProvider;
  private final DispatchProperties properties;

  CarbonAwareDispatchWorker(DispatchDeferredJobsUseCase dispatchUseCase,
                            CarbonIntensityProvider carbonIntensityProvider,
                            DispatchProperties properties) {
    this.dispatchUseCase = dispatchUseCase;
    this.carbonIntensityProvider = carbonIntensityProvider;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${gatewai.dispatch.poll-interval-ms:5000}")
  void tick() {
    Map<String, Double> intensities = new LinkedHashMap<>();
    for (String zone : properties.getCandidateZones()) {
      intensities.put(zone, carbonIntensityProvider.gramsCo2PerKwh(zone));
    }
    dispatchUseCase.dispatchPending(intensities);
  }
}
