package io.github.yourimartin.gatewai.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.model.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.CalibrationTarget;
import io.github.yourimartin.gatewai.domain.model.ConformalCalibration;
import io.github.yourimartin.gatewai.domain.model.ConformalGuarantee;
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
  @DisplayName("a non-admin key cannot read or move a threshold")
  void nonAdminIsForbidden() throws Exception {
    mockMvc.perform(get("/v1/admin/calibration").with(authentication(clientAuth())))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/v1/admin/calibration").with(authentication(clientAuth())))
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
