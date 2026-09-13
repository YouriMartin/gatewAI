package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.CarbonZoneSource;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.RegionProvenance;
import io.github.yourimartin.gatewai.domain.model.RequestLog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaRequestLogAdapterTest {

  @Mock
  private SpringDataRequestLogRepository jpaRepository;

  @InjectMocks
  private JpaRequestLogAdapter adapter;

  @Captor
  private ArgumentCaptor<RequestLogEntity> entityCaptor;

  @Test
  void saveDelegatesToSpringData() {
    RequestLog log = new RequestLog(
        UUID.randomUUID(), "corr-1", Instant.now(), "claude-3",
        "a".repeat(64), 10, 5, 15, 200L, "client-1",
        new GreenMetrics(0.3, 0.01, 2.3, 0.6, 1.5), GreenProvenance.UNKNOWN, false
    );

    when(jpaRepository.save(any(RequestLogEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    adapter.save(log);

    verify(jpaRepository).save(entityCaptor.capture());
    RequestLogEntity captured = entityCaptor.getValue();
    assertEquals(log.id(), captured.toDomain().id());
    assertEquals(log.model(), captured.toDomain().model());
    assertEquals(log.clientId(), captured.toDomain().clientId());
    assertEquals(log.green(), captured.toDomain().green());
  }

  @Test
  void roundTripDomainToEntityToDomain() {
    RequestLog original = new RequestLog(
        UUID.randomUUID(), "corr-1", Instant.parse("2026-06-01T12:00:00Z"),
        "claude-3-opus", "b".repeat(64),
        100, 50, 150, 1234L, "tenant-42",
        new GreenMetrics(2.25, 0.75, 172.5, 22.5, 60.0), GreenProvenance.UNKNOWN, true
    );

    RequestLogEntity entity = new RequestLogEntity(original);
    RequestLog restored = entity.toDomain();

    assertEquals(original, restored);
  }

  @Test
  void roundTripWithNullClientIdAndCorrelationId() {
    RequestLog original = new RequestLog(
        UUID.randomUUID(), null, Instant.parse("2026-06-01T12:00:00Z"),
        "claude-3", "c".repeat(64),
        10, 5, 15, 100L, null, GreenMetrics.ZERO, GreenProvenance.UNKNOWN, false
    );

    RequestLogEntity entity = new RequestLogEntity(original);
    RequestLog restored = entity.toDomain();

    assertEquals(original, restored);
  }

  @Test
  void roundTripsTheGreenProvenanceSoAStoredRowExplainsItself() {
    GreenProvenance provenance = new GreenProvenance("anthropic", "US-MIDA-PJM",
        350.0, CarbonZoneSource.PROVIDER_REGION, "SE", RegionProvenance.ASSUMED,
        EnergySource.MODELLED, 1.12);
    RequestLog original = new RequestLog(
        UUID.randomUUID(), "corr-9", Instant.parse("2026-09-01T09:00:00Z"),
        "claude-opus-4-8", "d".repeat(64), 320, 322, 642, 900L, "tenant-1",
        new GreenMetrics(0.00963, 0.0059471552, 2.08150432, 0.0, 0.0),
        provenance, false);

    RequestLog restored = new RequestLogEntity(original).toDomain();

    assertEquals(original, restored);
    // The acceptance criterion: gCO2 re-derives from the row's own columns, with no
    // reference to today's registry or provider configuration.
    assertEquals(restored.green().gramsCo2(),
        restored.green().energyKwh()
            * restored.provenance().gridIntensityGramsPerKwh(), 1e-9);
    // And the dispatch zone that was recorded but not applied survives the trip.
    assertEquals("SE", restored.provenance().dispatchZone());
    assertEquals(true, restored.provenance().dispatchRecordedOnly());
  }

  @Test
  void aRowWrittenBeforeLotC5ComesBackUnknownRatherThanFabricated() {
    RequestLog preC5 = new RequestLog(
        UUID.randomUUID(), "corr-old", Instant.parse("2026-06-01T12:00:00Z"),
        "claude-3", "e".repeat(64), 10, 5, 15, 100L, "tenant-1",
        new GreenMetrics(0.3, 0.01, 2.3, 0.6, 1.5), null, false);

    // Null provenance is what a pre-V9 row deserialises to: the columns are NULL.
    RequestLog restored = new RequestLogEntity(preC5).toDomain();

    assertEquals(GreenProvenance.UNKNOWN, restored.provenance());
    assertEquals(2.3, restored.green().gramsCo2());
  }
}
