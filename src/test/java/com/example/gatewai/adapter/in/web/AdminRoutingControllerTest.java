package com.example.gatewai.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.gatewai.domain.model.RoutingConfig;
import com.example.gatewai.domain.port.in.RoutingConfigUseCase;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminRoutingController.class)
@Import(SecurityConfig.class)
class AdminRoutingControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private RoutingConfigUseCase useCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static ApiKeyAuthentication adminAuth() {
    return new ApiKeyAuthentication("admin-id", "admin",
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private static RoutingConfig config() {
    return new RoutingConfig("heuristic", 100, 500, List.of("refactor"));
  }

  private static final String BODY = """
      {
        "strategy": "llm",
        "entry_length_threshold": 120,
        "premium_length_threshold": 600,
        "premium_keywords": ["refactor", "debug"]
      }
      """;

  @Test
  void getReturnsCurrentConfig() throws Exception {
    when(useCase.current()).thenReturn(config());

    mockMvc.perform(get("/v1/admin/routing")
            .with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategy").value("heuristic"))
        .andExpect(jsonPath("$.entry_length_threshold").value(100))
        .andExpect(jsonPath("$.premium_keywords[0]").value("refactor"));
  }

  @Test
  void putAppliesAndReturnsUpdatedConfig() throws Exception {
    when(useCase.current()).thenReturn(
        new RoutingConfig("llm", 120, 600, List.of("refactor", "debug")));

    mockMvc.perform(put("/v1/admin/routing")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)
            .with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.strategy").value("llm"))
        .andExpect(jsonPath("$.premium_length_threshold").value(600));
  }

  @Test
  void putReturns400ForInvalidConfig() throws Exception {
    doThrow(new IllegalArgumentException("bad")).when(useCase).update(any());

    mockMvc.perform(put("/v1/admin/routing")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)
            .with(authentication(adminAuth())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void nonAdminIsForbidden() throws Exception {
    mockMvc.perform(get("/v1/admin/routing")
            .with(authentication(new ApiKeyAuthentication("u", "user"))))
        .andExpect(status().isForbidden());
  }
}
