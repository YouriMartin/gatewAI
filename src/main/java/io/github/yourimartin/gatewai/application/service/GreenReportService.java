package io.github.yourimartin.gatewai.application.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ReportAggregator;
import io.github.yourimartin.gatewai.domain.port.in.GenerateGreenReportUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.springframework.stereotype.Service;

/**
 * Generates a {@link GreenReport} by fetching the request logs in range and
 * aggregating them. Aggregation is delegated to the pure-domain
 * {@link ReportAggregator}.
 *
 * <p>Rows are aggregated in memory: adequate for an MVP; a very large range
 * would warrant a SQL {@code GROUP BY} aggregation instead.
 *
 * <p>The scope boundary (v3 lot C.1) is resolved against the <b>current</b>
 * registry: a served model id maps to its declared {@link EnergySource}, and a
 * model that has since left the registry is assumed accounted rather than
 * silently dropped from the totals. Persisting the label per row is lot C.5.
 */
@Service
class GreenReportService implements GenerateGreenReportUseCase {

  private final RequestLogRepository requestLogRepository;
  private final ReportAggregator aggregator;

  GreenReportService(RequestLogRepository requestLogRepository,
                     ModelRegistry modelRegistry) {
    this.requestLogRepository = requestLogRepository;
    this.aggregator = new ReportAggregator(modelId -> modelRegistry
        .findByModelId(modelId)
        .map(ModelDefinition::energySource)
        .orElse(EnergySource.MODELLED));
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
