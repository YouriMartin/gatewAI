package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.EmissionsScope;
import io.github.yourimartin.gatewai.domain.model.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RequestLog;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GreenReportServiceTest {

  private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");

  @Mock
  private RequestLogRepository requestLogRepository;

  @Mock
  private ModelRegistry modelRegistry;

  private GreenReportService service;

  @BeforeEach
  void setUp() {
    service = new GreenReportService(requestLogRepository, modelRegistry);
  }

  @Test
  void generatesReportFromRepositoryRows() {
    RequestLog log = new RequestLog(
        UUID.randomUUID(), "corr-1", Instant.now(), "haiku", "hash", 1, 1, 2, 0L,
        "client", new GreenMetrics(0.002, 0.001, 0.46, 0.013, 0.69), GreenProvenance.UNKNOWN, false);
    when(requestLogRepository.findBetween(FROM, TO)).thenReturn(List.of(log));

    GreenReport report = service.generate(FROM, TO);

    assertEquals(1, report.totalRequests());
    assertEquals(FROM, report.from());
    assertEquals(0.013, report.totalCostAvoidedEur(), 1e-9);
    verify(requestLogRepository).findBetween(FROM, TO);
  }

  @Test
  void marksRequestsServedByUnaccountedModelsAsExcludedFromScope() {
    RequestLog log = new RequestLog(
        UUID.randomUUID(), "corr-2", Instant.now(), "qwen2.5:3b", "hash", 1, 1, 2, 0L,
        "client", new GreenMetrics(0.0, 0.0, 0.0, 0.0, 0.0), GreenProvenance.UNKNOWN, false);
    when(requestLogRepository.findBetween(FROM, TO)).thenReturn(List.of(log));
    when(modelRegistry.findByModelId("qwen2.5:3b")).thenReturn(Optional.of(
        new ModelDefinition("local-large", "ollama", "qwen2.5:3b", 0.0,
            EnergyProfile.NOT_ACCOUNTED, ModelTier.CLOUD_PREMIUM)));

    GreenReport report = service.generate(FROM, TO);

    assertEquals(1, report.excludedRequests());
    assertEquals(EmissionsScope.ALL_EXCLUDED, report.emissionsScope());
  }

  @Test
  void treatsAModelMissingFromTheRegistryAsAccountedRatherThanDroppingIt() {
    RequestLog log = new RequestLog(
        UUID.randomUUID(), "corr-3", Instant.now(), "retired-model", "hash", 1, 1, 2,
        0L, "client", new GreenMetrics(0.002, 0.001, 0.46, 0.0, 0.0), GreenProvenance.UNKNOWN, false);
    when(requestLogRepository.findBetween(FROM, TO)).thenReturn(List.of(log));
    when(modelRegistry.findByModelId("retired-model")).thenReturn(Optional.empty());

    GreenReport report = service.generate(FROM, TO);

    assertEquals(0, report.excludedRequests());
    assertEquals(EmissionsScope.ALL_ACCOUNTED, report.emissionsScope());
  }

  @Test
  void dailyFetchesRangeAndBucketsByDay() {
    when(requestLogRepository.findBetween(FROM, TO)).thenReturn(List.of());

    List<GreenReport> series = service.daily(FROM, TO);

    assertEquals(29, series.size());
    verify(requestLogRepository).findBetween(FROM, TO);
  }

  @Test
  void dailyRejectsInvertedRange() {
    assertThrows(IllegalArgumentException.class, () -> service.daily(TO, FROM));
  }

  @Test
  void dailyRejectsTooLargeRange() {
    Instant from = Instant.parse("2024-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-01-01T00:00:00Z");

    assertThrows(IllegalArgumentException.class, () -> service.daily(from, to));
  }
}
