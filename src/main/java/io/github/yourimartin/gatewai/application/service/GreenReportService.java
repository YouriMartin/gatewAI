package io.github.yourimartin.gatewai.application.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.model.ReportAggregator;
import io.github.yourimartin.gatewai.domain.port.in.GenerateGreenReportUseCase;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Service;

/**
 * Generates a {@link GreenReport} by fetching the request logs in range and
 * aggregating them. Aggregation is delegated to the pure-domain
 * {@link ReportAggregator}.
 *
 * <p>Rows are aggregated in memory: adequate for an MVP; a very large range
 * would warrant a SQL {@code GROUP BY} aggregation instead.
 */
@Service
class GreenReportService implements GenerateGreenReportUseCase {

  private final RequestLogRepository requestLogRepository;
  private final ReportAggregator aggregator = new ReportAggregator();

  GreenReportService(RequestLogRepository requestLogRepository) {
    this.requestLogRepository = requestLogRepository;
  }

  private static final long MAX_DAYS = 366;

  @Override
  public GreenReport generate(Instant from, Instant to) {
    return aggregator.aggregate(
        requestLogRepository.findBetween(from, to), from, to);
  }

  @Override
  public List<GreenReport> daily(Instant from, Instant to) {
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    long days = ChronoUnit.DAYS.between(
        from.truncatedTo(ChronoUnit.DAYS), to.truncatedTo(ChronoUnit.DAYS)) + 1;
    if (days > MAX_DAYS) {
      throw new IllegalArgumentException("range too large (max 366 days)");
    }
    return aggregator.aggregateDaily(
        requestLogRepository.findBetween(from, to), from, to);
  }
}
