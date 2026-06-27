package com.example.gatewai.application.service;

import java.time.Instant;

import com.example.gatewai.domain.model.GreenReport;
import com.example.gatewai.domain.model.ReportAggregator;
import com.example.gatewai.domain.port.in.GenerateGreenReportUseCase;
import com.example.gatewai.domain.port.out.RequestLogRepository;

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

  @Override
  public GreenReport generate(Instant from, Instant to) {
    return aggregator.aggregate(
        requestLogRepository.findBetween(from, to), from, to);
  }
}
