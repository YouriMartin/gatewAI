package io.github.yourimartin.gatewai.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.GreenReport;
import io.github.yourimartin.gatewai.domain.port.in.GenerateGreenReportUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GreenReportController.class)
@Import(SecurityConfig.class)
class GreenReportControllerTest {

  private static final String FROM = "2026-06-01T00:00:00Z";
  private static final String TO = "2026-06-30T00:00:00Z";

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private GenerateGreenReportUseCase useCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static ApiKeyAuthentication auth() {
    return new ApiKeyAuthentication("test-client-id", "test-client");
  }

  private static GreenReport report() {
    return new GreenReport(
        Instant.parse(FROM), Instant.parse(TO),
        3, 1, 0.017, 0.028, 0.003, 1.61, 1.84,
        Map.of("haiku", 1L, "sonnet", 2L), Map.of());
  }

  @Test
  void returnsJsonReportByDefault() throws Exception {
    when(useCase.generate(any(), any())).thenReturn(report());

    mockMvc.perform(get("/v1/reports/green")
            .param("from", FROM).param("to", TO)
            .with(authentication(auth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_requests").value(3))
        .andExpect(jsonPath("$.cache_hits").value(1))
        .andExpect(jsonPath("$.total_cost_avoided_eur").value(0.028))
        .andExpect(jsonPath("$.model_mix.sonnet").value(2))
        .andExpect(jsonPath("$.emissions_scope").value("ALL_ACCOUNTED"))
        .andExpect(jsonPath("$.excluded_requests").value(0));
  }

  @Test
  void jsonReportCarriesTheScopeBoundaryWhenModelsAreExcluded() throws Exception {
    when(useCase.generate(any(), any())).thenReturn(new GreenReport(
        Instant.parse(FROM), Instant.parse(TO),
        3, 1, 0.0, 0.0, 0.0, 0.0, 0.0,
        Map.of("qwen2.5:3b", 3L), Map.of("qwen2.5:3b", 2L)));

    mockMvc.perform(get("/v1/reports/green")
            .param("from", FROM).param("to", TO)
            .with(authentication(auth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total_grams_co2").value(0.0))
        .andExpect(jsonPath("$.emissions_scope").value("ALL_EXCLUDED"))
        .andExpect(jsonPath("$.excluded_requests").value(2))
        .andExpect(jsonPath("$.excluded_models[0]").value("qwen2.5:3b"))
        .andExpect(jsonPath("$.emissions_scope_note")
            .value(org.hamcrest.Matchers.containsString("excluded from scope")));
  }

  @Test
  void returnsCsvWhenRequested() throws Exception {
    when(useCase.generate(any(), any())).thenReturn(report());

    mockMvc.perform(get("/v1/reports/green")
            .param("from", FROM).param("to", TO).param("format", "csv")
            .with(authentication(auth())))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(
            MediaType.parseMediaType("text/csv")))
        .andExpect(content().string(
            org.hamcrest.Matchers.containsString("section,metric,value,unit,reference")));
  }

  @Test
  void returnsPdfWhenRequested() throws Exception {
    when(useCase.generate(any(), any())).thenReturn(report());

    mockMvc.perform(get("/v1/reports/green")
            .param("from", FROM).param("to", TO).param("format", "pdf")
            .with(authentication(auth())))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF));
  }

  @Test
  void returns400ForInvalidDate() throws Exception {
    mockMvc.perform(get("/v1/reports/green")
            .param("from", "not-a-date").param("to", TO)
            .with(authentication(auth())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void seriesReturnsDailyPoints() throws Exception {
    when(useCase.daily(any(), any())).thenReturn(List.of(report(), report()));

    mockMvc.perform(get("/v1/reports/green/series")
            .param("from", FROM).param("to", TO)
            .with(authentication(auth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].total_requests").value(3));
  }

  @Test
  void seriesReturns400ForTooLargeRange() throws Exception {
    when(useCase.daily(any(), any()))
        .thenThrow(new IllegalArgumentException("range too large"));

    mockMvc.perform(get("/v1/reports/green/series")
            .param("from", FROM).param("to", TO)
            .with(authentication(auth())))
        .andExpect(status().isBadRequest());
  }
}
