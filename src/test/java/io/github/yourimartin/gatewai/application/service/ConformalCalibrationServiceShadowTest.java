package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationSource;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalSpread;
import io.github.yourimartin.gatewai.domain.model.calibration.FitSummary;
import io.github.yourimartin.gatewai.domain.model.calibration.ShadowFit;
import io.github.yourimartin.gatewai.domain.model.calibration.ShadowLabel;
import io.github.yourimartin.gatewai.domain.model.routing.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeRubric;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeVerdict;
import io.github.yourimartin.gatewai.domain.port.out.CalibrationStore;
import io.github.yourimartin.gatewai.domain.port.out.LabelledCaseSource;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;
import io.github.yourimartin.gatewai.domain.port.out.ShadowLabelHistory;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shadow fit of {@link ConformalCalibrationService} (v4 D.3, ADR 0019): which
 * labels count, the floor, the minimum sample and the summary. Split from
 * {@link ConformalCalibrationServiceTest}, which covers the labelled-set fit.
 */
class ConformalCalibrationServiceShadowTest {

  private static final double FIXED_CACHE_THRESHOLD = 0.92;
  private static final String JUDGE = "judge-model";
  private static final String MODEL = "nomic-embed-text";
  private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

  private final List<ShadowLabel> labels = new ArrayList<>();
  private CalibrationStore store;
  private LabelledCaseSource labelledCases;
  private TextEmbedder embedder;
  private RoutingConfigPort routingConfig;
  private ShadowLabelHistory shadowLabels;

  @BeforeEach
  void setUp() {
    store = new InMemoryCalibrationStore();
    labelledCases = mock(LabelledCaseSource.class);
    embedder = mock(TextEmbedder.class);
    routingConfig = mock(RoutingConfigPort.class);
    shadowLabels = mock(ShadowLabelHistory.class);
    when(embedder.modelId()).thenReturn(MODEL);
    when(routingConfig.get()).thenReturn(new RoutingConfig("embedding", 100, 500,
        List.of(), 0.60, List.of()));
    when(shadowLabels.labels(any())).thenAnswer(call -> {
      String client = call.getArgument(0);
      return labels.stream()
          .filter(label -> client == null || client.equals(label.clientId())).toList();
    });
    when(shadowLabels.notSampled(any())).thenReturn(7L);
  }

  /** Four judged pairs and three NO are enough here, so the rules are what is tested. */
  private ConformalCalibrationService service() {
    return new ConformalCalibrationService(store, labelledCases, embedder, routingConfig,
        shadowLabels, true, 0.10, 0.10, FIXED_CACHE_THRESHOLD, 4, 3, JUDGE);
  }

  @Test
  @DisplayName("fits the client's NO pairs above the floor and says what it left out")
  void fitsTheCountedNegativesAndSummarisesTheRest() {
    // Counted: NO at 0.93, 0.95, 0.97 and YES at 0.96, 0.99, recorded at the
    // fixed 0.92 that is the floor while nothing is calibrated.
    label("a", JudgeVerdict.NO, 0.93, 0.92);
    label("a", JudgeVerdict.NO, 0.95, 0.92);
    label("a", JudgeVerdict.NO, 0.97, 0.92);
    label("a", JudgeVerdict.YES, 0.96, 0.92);
    label("a", JudgeVerdict.YES, 0.99, 0.92);
    // Each excluded by one rule, in the order the rules apply.
    labels.add(new ShadowLabel("a", AT, JudgeOutcome.FAILED, null, JUDGE,
        JudgeRubric.VERSION, 0.95, 0.92, MODEL));
    label("a", JudgeVerdict.UNSURE, 0.95, 0.92);
    labels.add(new ShadowLabel("a", AT, JudgeOutcome.JUDGED, JudgeVerdict.NO, JUDGE,
        JudgeRubric.VERSION, null, null, null));
    labels.add(new ShadowLabel("a", AT, JudgeOutcome.JUDGED, JudgeVerdict.NO, JUDGE,
        "would-hit-v0", 0.95, 0.92, MODEL));
    labels.add(new ShadowLabel("a", AT, JudgeOutcome.JUDGED, JudgeVerdict.NO, "other",
        JudgeRubric.VERSION, 0.95, 0.92, MODEL));
    labels.add(new ShadowLabel("a", AT, JudgeOutcome.JUDGED, JudgeVerdict.NO, JUDGE,
        JudgeRubric.VERSION, 0.95, 0.92, "mxbai-embed-large"));
    label("a", JudgeVerdict.NO, 0.99, 0.96);
    label("a", JudgeVerdict.NO, 0.90, 0.85);
    label("b", JudgeVerdict.NO, 0.94, 0.92);
    when(shadowLabels.ambiguousBecomingServable("a", MODEL, 0.92, 0.97)).thenReturn(2L);

    CalibrationState state = service().calibrateCacheOnShadow("a", 0.25, null);

    ConformalCalibration fit = store.find(CalibrationTarget.CACHE, "a").orElseThrow();
    assertEquals(fit, state.calibration());
    assertEquals(CalibrationSource.SHADOW, fit.source());
    assertEquals(new ShadowFit(JUDGE, JudgeRubric.VERSION, 0.92), fit.shadowFit());
    // ceil(4 * 0.75) = 3 -> the third smallest NO similarity.
    assertEquals(0.97, fit.qhat(), 1e-12);
    assertEquals(3, fit.sampleSize(), "q-hat is fitted on the counted NO pairs only");
    assertTrue(store.find(CalibrationTarget.CACHE, null).isEmpty(),
        "a client fit leaves the global row alone");

    FitSummary.Shadow summary = (FitSummary.Shadow) fit.fitSummary();
    assertEquals(new FitSummary.Exclusions(7, 13, 1, 1, 1, 1, 1, 1, 1, 1),
        summary.exclusions());
    assertEquals(2, summary.yes());
    assertEquals(3, summary.no());
    assertEquals(0.5, summary.refusedServableShare(), 1e-12,
        "the YES at 0.96 is below the new threshold");
    assertEquals(2, summary.ambiguousBecomingServable());
    assertEquals(ConformalSpread.upperQuantile(3, 0.25, 0.90), summary.modelledRateP90());
    assertEquals(4, summary.minPairs());
    assertEquals(3, summary.minNegatives());
    assertEquals(AT, summary.labelsFrom());
    assertEquals(Map.of("a", new FitSummary.ClientCounts(13, 2, 3)),
        summary.perClient(), "client b's label was never read for client a");
  }

  @Test
  @DisplayName("below the minimum sample it refuses with the counts, and stores nothing")
  void refusesBelowTheMinimumSample() {
    label("a", JudgeVerdict.NO, 0.93, 0.92);
    label("a", JudgeVerdict.NO, 0.95, 0.92);
    label("a", JudgeVerdict.YES, 0.96, 0.92);
    label("a", JudgeVerdict.YES, 0.99, 0.92);

    IllegalStateException refused = assertThrows(IllegalStateException.class,
        () -> service().calibrateCacheOnShadow("a", 0.25, null));

    assertTrue(refused.getMessage().contains("4 judged pairs (2 NO)"),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("needs 4 (3 NO)"), refused.getMessage());
    assertTrue(store.findAll().isEmpty());
  }

  @Test
  @DisplayName("the NO minimum is never below what the quantile needs at that alpha")
  void theNegativeMinimumFollowsAlpha() {
    for (int i = 0; i < 10; i++) {
      label("a", JudgeVerdict.NO, 0.93 + i * 0.005, 0.92);
    }

    // alpha = 0.05 needs 19 negatives, whatever min-negatives says.
    IllegalStateException refused = assertThrows(IllegalStateException.class,
        () -> service().calibrateCacheOnShadow("a", 0.05, null));
    assertTrue(refused.getMessage().contains("(19 NO)"), refused.getMessage());
  }

  @Test
  @DisplayName("the floor is the client's own threshold, and a DELETE lowers it again")
  void theFloorIsTheThresholdInForceForTheScope() {
    store.save(clientRow("a", 0.97));
    // Recorded under the client's 0.97 row: they count while it is in force.
    label("a", JudgeVerdict.NO, 0.97, 0.97);
    label("a", JudgeVerdict.NO, 0.98, 0.97);
    label("a", JudgeVerdict.NO, 0.99, 0.97);
    label("a", JudgeVerdict.YES, 0.995, 0.97);

    ConformalCalibration refit = service().calibrateCacheOnShadow("a", 0.25, null)
        .calibration();
    assertEquals(0.97, refit.shadowFit().labelFloor(), 1e-12);

    // Back on the global (fixed 0.92) threshold, every label recorded under the
    // old 0.97 row is above the new floor: the fit has to start over.
    store.delete(CalibrationTarget.CACHE, "a");
    IllegalStateException refused = assertThrows(IllegalStateException.class,
        () -> service().calibrateCacheOnShadow("a", 0.25, null));
    assertTrue(refused.getMessage().contains("4 recorded above the floor"),
        refused.getMessage());
  }

  @Test
  @DisplayName("a pooled fit replaces the global row and breaks the pool down by client")
  void aPooledFitIsTheGlobalRow() {
    label("a", JudgeVerdict.NO, 0.93, 0.92);
    label("a", JudgeVerdict.NO, 0.95, 0.92);
    label("b", JudgeVerdict.NO, 0.97, 0.92);
    label("b", JudgeVerdict.YES, 0.99, 0.92);

    ConformalCalibrationService service = service();
    service.calibrateCacheOnShadow(null, 0.25, null);

    ConformalCalibration global = service.state(CalibrationTarget.CACHE).calibration();
    assertTrue(global.isGlobal());
    assertEquals(CalibrationSource.SHADOW, global.source());
    assertEquals(Map.of(
            "a", new FitSummary.ClientCounts(2, 0, 2),
            "b", new FitSummary.ClientCounts(2, 1, 1)),
        ((FitSummary.Shadow) global.fitSummary()).perClient());
  }

  @Test
  void aJudgeMustBeNamedAndAlphaMustBeARate() {
    ConformalCalibrationService noJudge = new ConformalCalibrationService(store,
        labelledCases, embedder, routingConfig, shadowLabels, true, 0.10, 0.10,
        FIXED_CACHE_THRESHOLD, 4, 3, " ");

    assertThrows(IllegalArgumentException.class,
        () -> noJudge.calibrateCacheOnShadow("a", 0.25, null));
    assertThrows(IllegalArgumentException.class,
        () -> service().calibrateCacheOnShadow("a", 1.5, null));
    assertThrows(IllegalArgumentException.class,
        () -> service().calibrateCacheOnShadow(" ", 0.25, null));
  }

  @Test
  @DisplayName("the judge named in the request is the one whose verdicts count")
  void theRequestedJudgeOverridesTheConfiguredOne() {
    for (String judge : List.of("other", "other", "other", "other")) {
      labels.add(new ShadowLabel("a", AT, JudgeOutcome.JUDGED, JudgeVerdict.NO, judge,
          JudgeRubric.VERSION, 0.95, 0.92, MODEL));
    }

    ConformalCalibration fit = service().calibrateCacheOnShadow("a", 0.25, "other")
        .calibration();

    assertEquals("other", fit.shadowFit().judgeModel());
    assertEquals(4, fit.sampleSize());
  }

  @Test
  @DisplayName("a client's valid row is in force for it; a stale one falls back")
  void aClientStateIsItsOwnValidRowOrTheGlobalOne() {
    store.save(clientRow("a", 0.97));
    store.save(new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.99, 50, "mxbai-embed-large", null,
        AT, "b", CalibrationSource.SHADOW, new ShadowFit(JUDGE, "would-hit-v1", 0.92),
        null));
    ConformalCalibrationService service = service();

    assertEquals(0.97, service.state(CalibrationTarget.CACHE, "a").effectiveThreshold());
    assertEquals(FIXED_CACHE_THRESHOLD,
        service.state(CalibrationTarget.CACHE, "b").effectiveThreshold(),
        "a row fitted on another embedding model never applies");
    assertEquals(FIXED_CACHE_THRESHOLD,
        service.state(CalibrationTarget.CACHE, "c").effectiveThreshold());
    assertEquals(CalibrationStatus.ABSENT,
        service.state(CalibrationTarget.ROUTING, "a").status(),
        "routing has no client rows");
  }

  @Test
  @DisplayName("client rows are listed with their own status; removing one returns it")
  void clientRowsAreListedAndRemovable() {
    store.save(clientRow("b", 0.98));
    store.save(clientRow("a", 0.97));
    store.save(new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.99, 50, "mxbai-embed-large", null,
        AT, "c", CalibrationSource.SHADOW, new ShadowFit(JUDGE, "would-hit-v1", 0.92),
        null));
    ConformalCalibrationService service = service();

    List<CalibrationState> clients = service.clientStates();
    assertEquals(List.of("a", "b", "c"),
        clients.stream().map(state -> state.calibration().clientId()).toList());
    assertEquals(CalibrationStatus.STALE, clients.get(2).status(),
        "a stale client row is listed, and says so");

    assertTrue(service.removeClientCalibration("a"));
    assertEquals(false, service.removeClientCalibration("a"));
    assertEquals(FIXED_CACHE_THRESHOLD,
        service.state(CalibrationTarget.CACHE, "a").effectiveThreshold(),
        "a removed client falls back to the global state at once");
    assertThrows(IllegalArgumentException.class, () -> service.removeClientCalibration(" "));
  }

  private void label(String client, JudgeVerdict verdict, double similarity,
                     double recordedThreshold) {
    labels.add(new ShadowLabel(client, AT, JudgeOutcome.JUDGED, verdict, JUDGE,
        JudgeRubric.VERSION, similarity, recordedThreshold, MODEL));
  }

  private static ConformalCalibration clientRow(String client, double qhat) {
    return new ConformalCalibration(CalibrationTarget.CACHE,
        ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, qhat, 50, MODEL, null, AT, client,
        CalibrationSource.SHADOW, new ShadowFit(JUDGE, JudgeRubric.VERSION, 0.92), null);
  }
}
