package io.github.yourimartin.gatewai.adapter.in.web.admin;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import io.github.yourimartin.gatewai.adapter.in.web.security.ApiKeyAuthentication;
import io.github.yourimartin.gatewai.adapter.in.web.security.SecurityConfig;
import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitCounts;
import io.github.yourimartin.gatewai.domain.model.shadow.WrongHitEstimate;
import io.github.yourimartin.gatewai.domain.port.in.ShadowAccuracyUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminShadowController.class)
@Import(SecurityConfig.class)
class AdminShadowControllerTest {

  private static final String FROM = "2026-11-01T00:00:00Z";
  private static final String TO = "2026-11-08T00:00:00Z";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ShadowAccuracyUseCase useCase;
  @MockitoBean
  private ApiClientRepository apiClientRepository;

  @Test
  void theRateItsIntervalItsSampleSizeAndTheJudgeLabelTravelTogether() throws Exception {
    when(useCase.wrongHitRates(Instant.parse(FROM), Instant.parse(TO))).thenReturn(List.of(
        WrongHitEstimate.of(new WouldHitCounts("c1", "judge", "would-hit-v1", 14, 0.9, 1,
            8, 2, 4, 0))));

    mockMvc.perform(get("/v1/admin/shadow/wrong-hits").param("from", FROM).param("to", TO)
            .with(authentication(admin())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].client_id").value("c1"))
        .andExpect(jsonPath("$[0].judged").value(10))
        .andExpect(jsonPath("$[0].unsure").value(4))
        .andExpect(jsonPath("$[0].wrong_hit_rate").value(0.2))
        .andExpect(jsonPath("$[0].interval_low").isNumber())
        .andExpect(jsonPath("$[0].interval_high").isNumber())
        .andExpect(jsonPath("$[0].confidence").value(0.95))
        .andExpect(jsonPath("$[0].label")
            .value("judged by judge (rubric would-hit-v1), not by a human"));
  }

  @Test
  void aBadPeriodIs400AndANonAdminIs403() throws Exception {
    mockMvc.perform(get("/v1/admin/shadow/wrong-hits").param("from", "yesterday").param("to", TO)
            .with(authentication(admin())))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/admin/shadow/wrong-hits").param("from", TO).param("to", FROM)
            .with(authentication(admin())))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/v1/admin/shadow/wrong-hits").param("from", FROM).param("to", TO)
            .with(authentication(new ApiKeyAuthentication("user-id", "user"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(useCase);
  }

  private static ApiKeyAuthentication admin() {
    return new ApiKeyAuthentication("admin-id", "admin",
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }
}
