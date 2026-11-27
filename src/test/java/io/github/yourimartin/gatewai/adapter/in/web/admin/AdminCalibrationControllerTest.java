package io.github.yourimartin.gatewai.adapter.in.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.TreeMap;

import io.github.yourimartin.gatewai.adapter.in.web.security.ApiKeyAuthentication;
import io.github.yourimartin.gatewai.adapter.in.web.security.SecurityConfig;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationSource;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.calibration.ConformalGuarantee;
import io.github.yourimartin.gatewai.domain.model.calibration.FitSummary;
import io.github.yourimartin.gatewai.domain.model.calibration.ShadowFit;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCalibrationController.class)
@Import(SecurityConfig.class)
class AdminCalibrationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private CalibrationUseCase useCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  @Test
  @DisplayName("reports what is in force, not just what is stored")
  void getReturnsTheEffectiveThresholdAndWhyItIsInForce() throws Exception {
    when(useCase.states()).thenReturn(List.of(calibrated(), absent()));

    mockMvc.perform(get("/v1/admin/calibration").with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].target").value("CACHE"))
        .andExpect(jsonPath("$[0].status").value("VALID"))
        .andExpect(jsonPath("$[0].applied").value(true))
        .andExpect(jsonPath("$[0].effectiveThreshold").value(0.94))
        .andExpect(jsonPath("$[0].guarantee").value("WRONG_ANSWER_RATE"))
        .andExpect(jsonPath("$[0].sampleSize").value(93))
        .andExpect(jsonPath("$[1].target").value("ROUTING"))
        .andExpect(jsonPath("$[1].status").value("ABSENT"))
        .andExpect(jsonPath("$[1].applied").value(false))
        .andExpect(jsonPath("$[1].effectiveThreshold").value(0.60));
  }

  @Test
  void postWithoutABodyRecalibratesAtTheConfiguredDefaults() throws Exception {
    when(useCase.recalibrate(null, null)).thenReturn(List.of(calibrated()));

    mockMvc.perform(post("/v1/admin/calibration").with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].alpha").value(0.10));

    verify(useCase).recalibrate(null, null);
  }

  @Test
  void postPassesTheRequestedRiskLevels() throws Exception {
    when(useCase.recalibrate(0.2, 0.01)).thenReturn(List.of(calibrated()));

    mockMvc.perform(post("/v1/admin/calibration")
            .with(authentication(adminAuth()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"routingAlpha\":0.2,\"cacheAlpha\":0.01}"))
        .andExpect(status().isOk());

    verify(useCase).recalibrate(0.2, 0.01);
  }

  @Test
  @DisplayName("a sample too small for the level asked for is a 409, not a 500")
  void postReportsAnImpossibleCalibrationAsAConflict() throws Exception {
    when(useCase.recalibrate(any(), any()))
        .thenThrow(new IllegalStateException("needs at least 99 cases"));

    mockMvc.perform(post("/v1/admin/calibration").with(authentication(adminAuth())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("calibration_failed"))
        .andExpect(jsonPath("$.error.message").value("needs at least 99 cases"));
  }

  @Test
  void postRejectsAnAlphaOutsideTheOpenUnitInterval() throws Exception {
    when(useCase.recalibrate(any(), any()))
        .thenThrow(new IllegalArgumentException("alpha must be in (0,1), was 1.5"));

    mockMvc.perform(post("/v1/admin/calibration")
            .with(authentication(adminAuth()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"cacheAlpha\":1.5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("invalid_alpha"));
  }

  @Test
  @DisplayName("GET lists the global rows, then each client's own, with who labelled them")
  void getListsClientRowsWithTheirProvenanceAndSummary() throws Exception {
    when(useCase.states()).thenReturn(List.of(calibrated()));
    when(useCase.clientStates()).thenReturn(List.of(clientRow()));

    mockMvc.perform(get("/v1/admin/calibration").with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].client").doesNotExist())
        .andExpect(jsonPath("$[0].labelledBy").value("hand-labelled cases"))
        .andExpect(jsonPath("$[1].client").value("client-a"))
        .andExpect(jsonPath("$[1].source").value("SHADOW"))
        .andExpect(jsonPath("$[1].labelledBy")
            .value("judged by judge (rubric would-hit-v1), not by a human"))
        .andExpect(jsonPath("$[1].labelFloor").value(0.92))
        .andExpect(jsonPath("$[1].fitSummary.type").value("shadow"))
        .andExpect(jsonPath("$[1].fitSummary.no").value(50))
        .andExpect(jsonPath("$[1].fitSummary.exclusions.unsure").value(2))
        .andExpect(jsonPath("$[1].fitSummary.modelledRateP90Basis")
            .value(FitSummaryView.P90_BASIS))
        .andExpect(jsonPath("$[1].fitSummary.perClient['client-a'].no").value(50));
  }

  @Test
  void aShadowFitPassesTheClientAlphaAndJudge() throws Exception {
    when(useCase.states()).thenReturn(List.of(calibrated()));
    when(useCase.clientStates()).thenReturn(List.of(clientRow()));

    mockMvc.perform(post("/v1/admin/calibration")
            .with(authentication(adminAuth()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"shadow\",\"client\":\"client-a\","
                + "\"cacheAlpha\":0.05,\"judgeModel\":\"judge\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[1].client").value("client-a"));

    verify(useCase).calibrateCacheOnShadow("client-a", 0.05, "judge");
  }

  @Test
  @DisplayName("a field the fit would ignore is refused, not dropped")
  void aRequestWhoseFieldsDoNotApplyIsABadRequest() throws Exception {
    for (String body : List.of("{\"source\":\"guessed\"}",
        "{\"source\":\"shadow\",\"routingAlpha\":0.1}",
        "{\"client\":\"client-a\"}", "{\"judgeModel\":\"judge\"}")) {
      mockMvc.perform(post("/v1/admin/calibration")
              .with(authentication(adminAuth()))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }
  }

  @Test
  @DisplayName("too few shadow labels is a 409 that says how many there are")
  void aShadowFitBelowTheMinimumIsAConflict() throws Exception {
    when(useCase.calibrateCacheOnShadow(any(), any(), any()))
        .thenThrow(new IllegalStateException("12 judged pairs (3 NO) count"));

    mockMvc.perform(post("/v1/admin/calibration")
            .with(authentication(adminAuth()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"shadow\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("calibration_failed"))
        .andExpect(jsonPath("$.error.message").value("12 judged pairs (3 NO) count"));
  }

  @Test
  void aShadowFitWithNoJudgeIsABadRequest() throws Exception {
    when(useCase.calibrateCacheOnShadow(any(), any(), any()))
        .thenThrow(new IllegalArgumentException("Name the judge whose verdicts count"));

    mockMvc.perform(post("/v1/admin/calibration")
            .with(authentication(adminAuth()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"source\":\"shadow\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("invalid_request"));
  }

  @Test
  void deletingAClientRowIs204AndAMissingOneIs404() throws Exception {
    when(useCase.removeClientCalibration("client-a")).thenReturn(true);

    mockMvc.perform(delete("/v1/admin/calibration/cache/client-a")
            .with(authentication(adminAuth())))
        .andExpect(status().isNoContent());
    mockMvc.perform(delete("/v1/admin/calibration/cache/client-b")
            .with(authentication(adminAuth())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("calibration_not_found"));
  }

  @Test
  @DisplayName("a non-admin key cannot read or move a threshold")
  void nonAdminIsForbidden() throws Exception {
    mockMvc.perform(get("/v1/admin/calibration").with(authentication(clientAuth())))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/v1/admin/calibration").with(authentication(clientAuth())))
        .andExpect(status().isForbidden());
    mockMvc.perform(delete("/v1/admin/calibration/cache/client-a")
            .with(authentication(clientAuth())))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc.perform(get("/v1/admin/calibration"))
        .andExpect(status().isUnauthorized());
  }

  private static CalibrationState calibrated() {
    return new CalibrationState(CalibrationTarget.CACHE, CalibrationStatus.VALID,
        new ConformalCalibration(CalibrationTarget.CACHE,
            ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.94, 93,
            "nomic-embed-text", null, Instant.parse("2026-08-11T10:00:00Z")),
        0.92);
  }

  private static CalibrationState clientRow() {
    TreeMap<String, FitSummary.ClientCounts> perClient = new TreeMap<>();
    perClient.put("client-a", new FitSummary.ClientCounts(300, 240, 50));
    return new CalibrationState(CalibrationTarget.CACHE, CalibrationStatus.VALID,
        new ConformalCalibration(CalibrationTarget.CACHE,
            ConformalGuarantee.WRONG_ANSWER_RATE, 0.10, 0.96, 50, "nomic-embed-text", null,
            Instant.parse("2026-11-27T10:00:00Z"), "client-a", CalibrationSource.SHADOW,
            new ShadowFit("judge", "would-hit-v1", 0.92),
            new FitSummary.Shadow(new FitSummary.Exclusions(40, 300, 3, 2, 0, 0, 0, 0, 5, 0),
                240, 50, Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-26T00:00:00Z"), 0.2, 4, 0.15, 200, 50, perClient)),
        0.92);
  }

  private static CalibrationState absent() {
    return new CalibrationState(CalibrationTarget.ROUTING, CalibrationStatus.ABSENT,
        null, 0.60);
  }

  private static ApiKeyAuthentication adminAuth() {
    return new ApiKeyAuthentication("admin-id", "admin",
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private static ApiKeyAuthentication clientAuth() {
    return new ApiKeyAuthentication("client-id", "client",
        List.of(new SimpleGrantedAuthority("ROLE_CLIENT")));
  }
}
