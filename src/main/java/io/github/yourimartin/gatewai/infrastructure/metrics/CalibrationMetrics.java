package io.github.yourimartin.gatewai.infrastructure.metrics;

import java.util.Locale;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Publishes the calibration state to Micrometer and to the startup log
 * (v2 batch 3).
 *
 * <p>A gateway silently running on fixed thresholds because its calibration went
 * stale is the failure this exists to prevent: the degradation is by design
 * invisible in the responses, so it has to be visible somewhere else.
 *
 * <p>{@code gatewai.conformal.calibration.stale} is 1 whenever the calibrated
 * threshold is <b>not</b> the one in force, whatever the reason, so a single
 * alert rule covers "never calibrated", "gone stale" and "switched off". The
 * reason is deliberately not a tag: it changes over the life of a process, and a
 * gauge whose tags move creates a new series every time it does. Read it from
 * {@code GET /v1/admin/calibration} or from the startup log.
 */
@Component
class CalibrationMetrics {

  private static final Logger LOG = LoggerFactory.getLogger(CalibrationMetrics.class);

  private final CalibrationUseCase calibrations;

  CalibrationMetrics(CalibrationUseCase calibrations, MeterRegistry registry) {
    this.calibrations = calibrations;

    for (CalibrationTarget target : CalibrationTarget.values()) {
      Gauge.builder("gatewai.conformal.calibration.stale",
              () -> stale(target))
          .tag("target", target.name().toLowerCase(Locale.ROOT))
          .description("1 when the fixed threshold is in force instead of a calibration")
          .register(registry);
      Gauge.builder("gatewai.conformal.threshold", () -> threshold(target))
          .tag("target", target.name().toLowerCase(Locale.ROOT))
          .description("The similarity threshold actually applied")
          .register(registry);
    }
  }

  /** States the calibration situation once, in the log, at startup. */
  @EventListener(ApplicationReadyEvent.class)
  void reportAtStartup() {
    for (CalibrationState state : calibrations.states()) {
      if (state.isApplied()) {
        LOG.info("{} threshold {} from a calibration ({}, alpha={}, n={}, fitted {})",
            state.target(), state.effectiveThreshold(),
            state.calibration().guarantee(), state.calibration().alpha(),
            state.calibration().sampleSize(), state.calibration().calibratedAt());
      } else {
        LOG.warn("{} threshold {} is the fixed configured value ({}). "
                + "POST /v1/admin/calibration to fit one from labelled data.",
            state.target(), state.effectiveThreshold(), state.status());
      }
    }
  }

  private double stale(CalibrationTarget target) {
    return calibrations.state(target).isApplied() ? 0 : 1;
  }

  private double threshold(CalibrationTarget target) {
    return calibrations.state(target).effectiveThreshold();
  }
}
