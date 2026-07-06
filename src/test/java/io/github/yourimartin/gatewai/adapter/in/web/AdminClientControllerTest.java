package io.github.yourimartin.gatewai.adapter.in.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.ApiClient;
import io.github.yourimartin.gatewai.domain.model.CreatedApiClient;
import io.github.yourimartin.gatewai.domain.port.in.ManageApiClientsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminClientController.class)
@Import(SecurityConfig.class)
class AdminClientControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ManageApiClientsUseCase useCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static ApiKeyAuthentication adminAuth() {
    return new ApiKeyAuthentication("admin-id", "admin",
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private static ApiKeyAuthentication userAuth() {
    return new ApiKeyAuthentication("user-id", "user");
  }

  private static ApiClient client(boolean admin) {
    return new ApiClient(UUID.randomUUID(), "acme", "hash",
        true, Instant.parse("2026-06-01T00:00:00Z"), admin);
  }

  @Test
  void createReturns201WithRawKey() throws Exception {
    when(useCase.create("acme", true))
        .thenReturn(new CreatedApiClient(client(true), "gw_secret"));

    mockMvc.perform(post("/v1/admin/clients")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"acme\",\"admin\":true}")
            .with(authentication(adminAuth())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.api_key").value("gw_secret"))
        .andExpect(jsonPath("$.client.name").value("acme"))
        .andExpect(jsonPath("$.client.admin").value(true));
  }

  @Test
  void listReturnsClients() throws Exception {
    when(useCase.list()).thenReturn(List.of(client(false)));

    mockMvc.perform(get("/v1/admin/clients")
            .with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("acme"))
        .andExpect(jsonPath("$[0].admin").value(false));
  }

  @Test
  void revokeReturns204() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc.perform(post("/v1/admin/clients/" + id + "/revoke")
            .with(authentication(adminAuth())))
        .andExpect(status().isNoContent());

    verify(useCase).revoke(id);
  }

  @Test
  void nonAdminIsForbidden() throws Exception {
    mockMvc.perform(get("/v1/admin/clients")
            .with(authentication(userAuth())))
        .andExpect(status().isForbidden());
  }
}
