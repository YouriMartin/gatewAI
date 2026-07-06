package io.github.yourimartin.gatewai.infrastructure.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.port.in.DispatchDeferredJobsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CarbonAwareDispatchWorkerTest {

  @Mock
  private DispatchDeferredJobsUseCase dispatchUseCase;

  @Mock
  private CarbonIntensityProvider carbonIntensityProvider;

  @Captor
  private ArgumentCaptor<Map<String, Double>> intensitiesCaptor;

  @Test
  void tickBuildsZoneIntensityMapAndDispatches() {
    DispatchProperties properties = new DispatchProperties();
    properties.setCandidateZones(List.of("FR", "SE"));
    when(carbonIntensityProvider.gramsCo2PerKwh("FR")).thenReturn(56.0);
    when(carbonIntensityProvider.gramsCo2PerKwh("SE")).thenReturn(30.0);

    CarbonAwareDispatchWorker worker = new CarbonAwareDispatchWorker(
        dispatchUseCase, carbonIntensityProvider, properties);

    worker.tick();

    verify(dispatchUseCase).dispatchPending(intensitiesCaptor.capture());
    Map<String, Double> intensities = intensitiesCaptor.getValue();
    assertEquals(56.0, intensities.get("FR"));
    assertEquals(30.0, intensities.get("SE"));
  }

  @Test
  void tickWithNoCandidateZonesDispatchesEmptyMap() {
    CarbonAwareDispatchWorker worker = new CarbonAwareDispatchWorker(
        dispatchUseCase, carbonIntensityProvider, new DispatchProperties());

    worker.tick();

    verify(dispatchUseCase).dispatchPending(Map.of());
  }
}
