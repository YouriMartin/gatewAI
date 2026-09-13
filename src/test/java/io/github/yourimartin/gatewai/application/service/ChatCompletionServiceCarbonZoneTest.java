package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.CarbonCalculator;
import io.github.yourimartin.gatewai.domain.model.CarbonZoneContext;
import io.github.yourimartin.gatewai.domain.model.CarbonZoneSource;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.ProviderRegion;
import io.github.yourimartin.gatewai.domain.model.RegionProvenance;
import io.github.yourimartin.gatewai.domain.model.RequestLog;
import io.github.yourimartin.gatewai.domain.model.ResolvedCarbonZone;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.LlmClient;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Per-request grid attribution (v3 lot C.3): the defect this closes is one
 * intensity — the gateway's own — pricing every request, so a {@code zone=FR}
 * gateway booked US compute at France's 56 gCO2/kWh.
 *
 * <p>Uses the real {@link GreenAccountant} rather than a mock, because the point
 * is the number that comes out, not the call that was made.
 */
@ExtendWith(MockitoExtension.class)
class ChatCompletionServiceCarbonZoneTest {

  private static final double GATEWAY_INTENSITY = 230.0;
  private static final double US_INTENSITY = 350.0;
  private static final double SWEDEN_INTENSITY = 30.0;

  /** Premium tier on a hosted API: 0.005 kWh per 1k tokens, US grid. */
  private static final ModelDefinition CLAUDE = new ModelDefinition(
      "claude-premium", "anthropic", "claude-opus-4-8", 0.015, 0.005,
      EnergySource.MODELLED, ModelTier.CLOUD_PREMIUM);

  /** Local tier on the operator's own box: excluded from scope (lot C.1). */
  private static final ModelDefinition QWEN = new ModelDefinition(
      "local-small", "ollama", "qwen2.5:0.5b", 0.0, 0.0,
      EnergySource.NOT_ACCOUNTED, ModelTier.LOCAL);

  /** A self-hosted box with a real coefficient, to see the dispatch zone bite. */
  private static final ModelDefinition VLLM = new ModelDefinition(
      "vllm-medium", "vllm", "mistral-large", 0.0, 0.004,
      EnergySource.MODELLED, ModelTier.CLOUD_ENTRY);

  @Mock
  private LlmClient llmClient;

  @Mock
  private RequestLogRepository requestLogRepository;

  @Mock
  private ModelRegistry modelRegistry;

  @Mock
  private CarbonIntensityProvider carbonIntensityProvider;

  @Mock
  private MetricsRecorder metricsRecorder;

  @Mock
  private ProviderRegions providerRegions;

  @Mock
  private CloudRegionZones cloudRegionZones;

  private ChatCompletionService service;

  @BeforeEach
  void setUp() {
    service = new ChatCompletionService(llmClient, requestLogRepository, modelRegistry,
        carbonIntensityProvider, new GreenAccountant(new CarbonCalculator()),
        metricsRecorder, providerRegions, cloudRegionZones);

    lenient().when(modelRegistry.findByModelId("claude-opus-4-8"))
        .thenReturn(Optional.of(CLAUDE));
    lenient().when(modelRegistry.findByModelId("qwen2.5:0.5b"))
        .thenReturn(Optional.of(QWEN));
    lenient().when(modelRegistry.findByModelId("mistral-large"))
        .thenReturn(Optional.of(VLLM));
    lenient().when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM))
        .thenReturn(List.of(CLAUDE));

    lenient().when(providerRegions.findByProvider("anthropic")).thenReturn(Optional.of(
        new ProviderRegion("anthropic", "US-MIDA-PJM", RegionProvenance.ASSUMED,
            null, false)));
    lenient().when(providerRegions.findByProvider("ollama")).thenReturn(Optional.of(
        new ProviderRegion("ollama", null, null, null, true)));
    lenient().when(providerRegions.findByProvider("vllm")).thenReturn(Optional.of(
        new ProviderRegion("vllm", "eu-west-3", RegionProvenance.KNOWN, 1.15, true)));

    lenient().when(cloudRegionZones.zoneFor("US-MIDA-PJM"))
        .thenReturn(Optional.of("US-MIDA-PJM"));
    lenient().when(cloudRegionZones.zoneFor("eu-west-3")).thenReturn(Optional.of("FR"));

    lenient().when(carbonIntensityProvider.gramsCo2PerKwh())
        .thenReturn(GATEWAY_INTENSITY);
    lenient().when(carbonIntensityProvider.gramsCo2PerKwh("US-MIDA-PJM"))
        .thenReturn(US_INTENSITY);
    lenient().when(carbonIntensityProvider.gramsCo2PerKwh("SE"))
        .thenReturn(SWEDEN_INTENSITY);
    lenient().when(carbonIntensityProvider.gramsCo2PerKwh("FR")).thenReturn(56.0);
  }

  @Test
  void theHostedTierBooksAtItsOwnGridWhileTheLocalTierBooksAtTheGateway() {
    when(llmClient.call(any()))
        .thenReturn(response("claude-opus-4-8", false))
        .thenReturn(response("qwen2.5:0.5b", false));

    service.complete(request());
    service.complete(request());

    List<RequestLog> logs = savedLogs(2);

    // 20 tokens x 0.005 kWh/1k = 0.0001 kWh, at the US grid: 0.035 gCO2.
    // Before C.3 this was 0.0001 x 230 = 0.023 — the gateway's grid, not Anthropic's.
    assertEquals(0.035, logs.get(0).green().gramsCo2(), 1e-9);
    // Same run, same gateway: the local tier is excluded from scope (0 kWh), and its
    // avoided figure prices the premium baseline at ANTHROPIC's grid, not here.
    assertEquals(0.0, logs.get(1).green().gramsCo2(), 1e-9);
    assertEquals(0.035, logs.get(1).green().gramsCo2Avoided(), 1e-9);
  }

  @Test
  void aDeferredJobAgainstAHostedApiRecordsTheChosenZoneAndAccountsAtTheProvider() {
    when(llmClient.call(any())).thenReturn(response("claude-opus-4-8", false));

    ScopedValue.where(CarbonZoneContext.CURRENT, "SE").run(() -> service.complete(request()));

    // Accounted at the provider's grid: deferring moved nothing in Anthropic's
    // datacenter. 0.0001 kWh x 350, not x 30.
    assertEquals(0.035, savedLogs(1).getFirst().green().gramsCo2(), 1e-9);

    // The chosen zone is kept as recorded-not-applied. Persisting it is lot C.5.
    ResolvedCarbonZone resolved = ScopedValue
        .where(CarbonZoneContext.CURRENT, "SE")
        .call(() -> service.resolveZone(CLAUDE));
    assertEquals("US-MIDA-PJM", resolved.zone());
    assertEquals(CarbonZoneSource.PROVIDER_REGION, resolved.source());
    assertEquals("SE", resolved.dispatchZone());
    assertTrue(resolved.dispatchRecordedOnly());
    assertFalse(resolved.dispatchApplied());
  }

  @Test
  void theDeferredPathIsUnchangedForAProviderTheOperatorControls() {
    when(llmClient.call(any())).thenReturn(response("mistral-large", false));

    ScopedValue.where(CarbonZoneContext.CURRENT, "SE").run(() -> service.complete(request()));

    // 20 tokens x 0.004 kWh/1k = 0.00008 kWh at Sweden's 30 gCO2/kWh = 0.0024.
    // The dispatch zone still wins on the operator's own box — that is the whole
    // point of carbon-aware dispatch, and C.3 must not regress it.
    assertEquals(0.0024, savedLogs(1).getFirst().green().gramsCo2(), 1e-9);

    ResolvedCarbonZone resolved = ScopedValue
        .where(CarbonZoneContext.CURRENT, "SE")
        .call(() -> service.resolveZone(VLLM));
    assertEquals("SE", resolved.zone());
    assertEquals(CarbonZoneSource.DISPATCH, resolved.source());
    assertTrue(resolved.dispatchApplied());
  }

  @Test
  void aProviderWithNoDeclaredRegionFallsBackToTheGatewayDefault() {
    when(llmClient.call(any())).thenReturn(response("qwen2.5:0.5b", false));

    service.complete(request());

    ResolvedCarbonZone resolved = service.resolveZone(QWEN);
    assertEquals(null, resolved.zone());
    assertEquals(CarbonZoneSource.GATEWAY_DEFAULT, resolved.source());
    verify(carbonIntensityProvider, atLeastOnce()).gramsCo2PerKwh();
  }

  @Test
  void aRegionThatCannotBeMappedFallsBackRatherThanGuessing() {
    when(providerRegions.findByProvider("anthropic")).thenReturn(Optional.of(
        new ProviderRegion("anthropic", "mars-north-1", null, null, false)));
    when(cloudRegionZones.zoneFor("mars-north-1")).thenReturn(Optional.empty());
    when(llmClient.call(any())).thenReturn(response("claude-opus-4-8", false));

    service.complete(request());

    // Gateway default: 0.0001 kWh x 230 = 0.023 gCO2.
    assertEquals(0.023, savedLogs(1).getFirst().green().gramsCo2(), 1e-9);
  }

  private static LlmRequest request() {
    return new LlmRequest("gatewai-auto",
        List.of(new LlmMessage("user", "hello")), 0.7, 256);
  }

  private static LlmResponse response(String model, boolean cacheHit) {
    return new LlmResponse(model, "Hi!", "stop", 12, 8, 20, cacheHit);
  }

  private List<RequestLog> savedLogs(int expected) {
    ArgumentCaptor<RequestLog> captor = ArgumentCaptor.forClass(RequestLog.class);
    verify(requestLogRepository, times(expected)).save(captor.capture());
    return captor.getAllValues();
  }
}
