package io.github.yourimartin.gatewai.domain.port.in;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.GreenReport;

/** Produces aggregated green reports over a date range (Phase 4.5 / 5.3). */
public interface GenerateGreenReportUseCase {

  GreenReport generate(Instant from, Instant to);

  /**
   * One aggregated report per day in the range (time series for charts).
   *
   * @throws IllegalArgumentException if the range is invalid or too large
   */
  List<GreenReport> daily(Instant from, Instant to);
}
