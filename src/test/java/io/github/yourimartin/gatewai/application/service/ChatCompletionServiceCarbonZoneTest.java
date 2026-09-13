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
import io.github.yourimartin.gatewai.domain.model.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.GreenProvenance;
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

  /**
   * Premium tier on a hosted API. Round coefficients and PUE 1.0 on the provider
   * keep the zone arithmetic readable — PUE itself is covered in
   * {@code CarbonCalculatorTest}.
   */
  private static final ModelDefinition CLAUDE = new ModelDefinition(
      "claude-premium", "anthropic", "claude-opus-4-8", 0.015,
      new EnergyProfile(0.001, 0.005, 0.0, EnergySource.MODELLED, false),
      ModelTier.CLOUD_PREMIUM);

  /** Local tier on the operator's own box: excluded from scope (lot C.1). */
  private static final ModelDefinition QWEN = new ModelDefinition(
      "local-small", "ollama", "qwen2.5:0.5b", 0.0, EnergyProfile.NOT_ACCOUNTED, ModelTier.LOCAL);

  /** A self-hosted box with a real coefficient, to see the dispatch zone bite. */
  private static final ModelDefinition VLLM = new ModelDefinition(
      "vllm-medium", "vllm", "mistral-large", 0.0,
      new EnergyProfile(0.001, 0.004, 0.0, EnergySource.MODELLED, false),
      ModelTier.CLOUD_ENTRY);

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
            1.0, false)));
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

    // 12 prompt x 0.001/1k + 8 completion x 0.005/1k = 5.2e-5 kWh, at the US grid:
    // 0.0182 gCO2. Before C.3 it was x 230 = 0.01196 — the gateway's grid, not
    // Anthropic's.
    assertEquals(0.0182, logs.get(0).green().gramsCo2(), 1e-9);
    // Same run, same gateway: the local tier is excluded from scope (0 kWh), and its
    // avoided figure prices the premium baseline at ANTHROPIC's grid, not here.
    assertEquals(0.0, logs.get(1).green().gramsCo2(), 1e-9);
    assertEquals(0.0182, logs.get(1).green().gramsCo2Avoided(), 1e-9);
  }

  @Test
  void aDeferredJobAgainstAHostedApiRecordsTheChosenZoneAndAccountsAtTheProvider() {
    when(llmClient.call(any())).thenReturn(response("claude-opus-4-8", false));

    ScopedValue.where(CarbonZoneContext.CURRENT, "SE").run(() -> service.complete(request()));

    // Accounted at the provider's grid: deferring moved nothing in Anthropic's
    // datacenter. 5.2e-5 kWh x 350, not x 30.
    assertEquals(0.0182, savedLogs(1).getFirst().green().gramsCo2(), 1e-9);

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

    // (12 x 0.001 + 8 x 0.004)/1k = 4.4e-5 kWh, x PUE 1.15 = 5.06e-5, at Sweden's
    // 30 gCO2/kWh = 0.001518. The dispatch zone still wins on the operator's own
    // box — that is the point of carbon-aware dispatch, and C.3 must not regress it.
    assertEquals(0.001518, savedLogs(1).getFirst().green().gramsCo2(), 1e-9);

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
        // No PUE either: the documented default applies on top.
        new ProviderRegion("anthropic", "mars-north-1", null, null, false)));
    when(cloudRegionZones.zoneFor("mars-north-1")).thenReturn(Optional.empty());
    when(llmClient.call(any())).thenReturn(response("claude-opus-4-8", false));

    service.complete(request());

    // Gateway default zone, and the default PUE 1.2 since this provider declares
    // none: 5.2e-5 kWh x 1.2 x 230 = 0.014352 gCO2.
    assertEquals(0.014352, savedLogs(1).getFirst().green().gramsCo2(), 1e-9);
  }

  @Test
  void aLongPromptAndItsMirrorImageNoLongerProduceTheSameRow() {
    when(llmClient.call(any()))
        .thenReturn(new LlmResponse("claude-opus-4-8", "…", "stop", 10_000, 50, 10_050, false))
        .thenReturn(new LlmResponse("claude-opus-4-8", "…", "stop", 50, 10_000, 10_050, false));

    service.complete(request());
    service.complete(request());

    List<RequestLog> logs = savedLogs(2);
    // Same 10 050 tokens both ways. Reading them: 10 x 0.001 + 0.05 x 0.005 =
    // 0.01025 kWh. Generating them: 0.05 x 0.001 + 10 x 0.005 = 0.05005 kWh.
    assertEquals(0.01025, logs.get(0).green().energyKwh(), 1e-9);
    assertEquals(0.05005, logs.get(1).green().energyKwh(), 1e-9);
    assertTrue(logs.get(1).green().gramsCo2() > logs.get(0).green().gramsCo2(),
        "a scalar per-1k-token coefficient would have made these two identical");
    // Cost is billed on the total, so it is the same both ways — as it should be.
    assertEquals(logs.get(0).green().costEur(), logs.get(1).green().costEur(), 1e-12);
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

  @Test
  void eachRowStoresTheProvenanceThatExplainsItsOwnFigure() {
    when(llmClient.call(any())).thenReturn(response("claude-opus-4-8", false));

    ScopedValue.where(CarbonZoneContext.CURRENT, "SE").run(() -> service.complete(request()));

    RequestLog log = savedLogs(1).getFirst();
    GreenProvenance provenance = log.provenance();

    assertEquals("anthropic", provenance.provider());
    assertEquals("US-MIDA-PJM", provenance.gridZone());
    assertEquals(US_INTENSITY, provenance.gridIntensityGramsPerKwh());
    assertEquals(CarbonZoneSource.PROVIDER_REGION, provenance.gridZoneSource());
    assertEquals(RegionProvenance.ASSUMED, provenance.regionProvenance());
    assertEquals(EnergySource.MODELLED, provenance.energySource());
    assertEquals(1.0, provenance.pue());
    // Recorded, not applied — the C.3 distinction, now on the row (C.5).
    assertEquals("SE", provenance.dispatchZone());
    assertTrue(provenance.dispatchRecordedOnly());
    // And the stored figure checks out against the stored intensity.
    assertEquals(log.green().gramsCo2(),
        log.green().energyKwh() * provenance.gridIntensityGramsPerKwh(), 1e-12);
  }

  @Test
  void editingACoefficientChangesNewRowsAndLeavesHistoryAlone() {
    when(llmClient.call(any())).thenReturn(response("claude-opus-4-8", false));
    service.complete(request());

    // The operator sources a better decode figure and doubles it.
    ModelDefinition rebased = new ModelDefinition(
        "claude-premium", "anthropic", "claude-opus-4-8", 0.015,
        new EnergyProfile(0.001, 0.010, 0.0, EnergySource.MODELLED, false),
        ModelTier.CLOUD_PREMIUM);
    when(modelRegistry.findByModelId("claude-opus-4-8")).thenReturn(Optional.of(rebased));
    when(modelRegistry.findByTier(ModelTier.CLOUD_PREMIUM)).thenReturn(List.of(rebased));
    service.complete(request());

    List<RequestLog> logs = savedLogs(2);
    // 12 x 0.001 + 8 x 0.005 = 5.2e-5 kWh, then 12 x 0.001 + 8 x 0.010 = 9.2e-5.
    assertEquals(5.2e-5, logs.get(0).green().energyKwh(), 1e-12);
    assertEquals(9.2e-5, logs.get(1).green().energyKwh(), 1e-12);
    // History is untouched: the old row keeps its own number AND its own explanation.
    assertEquals(5.2e-5 * US_INTENSITY, logs.get(0).green().gramsCo2(), 1e-12);
    assertEquals(US_INTENSITY, logs.get(0).provenance().gridIntensityGramsPerKwh());
  }
}
