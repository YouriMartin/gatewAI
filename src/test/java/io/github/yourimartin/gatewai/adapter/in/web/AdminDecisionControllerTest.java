package io.github.yourimartin.gatewai.adapter.in.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.AttributionReport;
import io.github.yourimartin.gatewai.domain.model.AttributionStatus;
import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.CalibrationStatus;
import io.github.yourimartin.gatewai.domain.model.ClassificationJustification;
import io.github.yourimartin.gatewai.domain.model.ClassificationStrategy;
import io.github.yourimartin.gatewai.domain.model.ConformalStatus;
import io.github.yourimartin.gatewai.domain.model.Counterfactual;
import io.github.yourimartin.gatewai.domain.model.CounterfactualReport;
import io.github.yourimartin.gatewai.domain.model.CounterfactualStatus;
import io.github.yourimartin.gatewai.domain.model.DecisionExplanation;
import io.github.yourimartin.gatewai.domain.model.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.ExplanationProvenance;
import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.SegmentAttribution;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;
import io.github.yourimartin.gatewai.domain.port.in.DecisionExplanationUseCase;
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

@WebMvcTest(AdminDecisionController.class)
@Import(SecurityConfig.class)
class AdminDecisionControllerTest {

  private static final Instant NOW = Instant.parse("2026-08-14T10:00:00Z");

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private DecisionExplanationUseCase useCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static ApiKeyAuthentication adminAuth() {
    return new ApiKeyAuthentication("admin-id", "admin",
        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  @Test
  @DisplayName("the trace comes back as stored, both halves")
  void getReturnsTheStoredDecision() throws Exception {
    when(useCase.find("req-1")).thenReturn(Optional.of(traced()));

    mockMvc.perform(get("/v1/admin/decisions/req-1")
            .with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value("req-1"))
        .andExpect(jsonPath("$.cache.outcome").value("MISS"))
        .andExpect(jsonPath("$.cache.runnerUpScore").value(0.42))
        .andExpect(jsonPath("$.routing.chosenTier").value("CLOUD_PREMIUM"))
        .andExpect(jsonPath("$.routing.decisionReason").value("MATCH"))
        .andExpect(jsonPath("$.routing.confidence.margin").value(0.12))
        .andExpect(jsonPath("$.routing.confidence.conformalSet[0]")
            .value("CLOUD_PREMIUM"))
        // The justification is a sealed interface, serialized by concrete type:
        // an Embedding must arrive with its scores, not as an empty object.
        .andExpect(jsonPath("$.routing.justification.topScore").value(0.81))
        .andExpect(jsonPath("$.routing.routingConfigVersion").value("cfg-1"));
  }

  @Test
  @DisplayName("an unrecorded id is a 404 that says why it might be missing")
  void getReturns404WhenNothingWasRecorded() throws Exception {
    when(useCase.find(anyString())).thenReturn(Optional.empty());

    mockMvc.perform(get("/v1/admin/decisions/unknown")
            .with(authentication(adminAuth())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("decision_not_found"));
  }

  @Test
  @DisplayName("the history lists recent requests, newest first")
  void listReturnsRecentDecisions() throws Exception {
    when(useCase.recent(anyInt())).thenReturn(List.of(traced()));

    mockMvc.perform(get("/v1/admin/decisions?limit=5")
            .with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].correlationId").value("req-1"))
        .andExpect(jsonPath("$[0].routing.chosenModelId").value("qwen3:14b"));
  }

  @Test
  @DisplayName("explaining a prompt returns the analysis and no decision")
  void explainPrompt() throws Exception {
    when(useCase.explainPrompt("Refactor the architecture."))
        .thenReturn(promptExplanation());

    mockMvc.perform(post("/v1/admin/decisions/explain")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"prompt\":\"Refactor the architecture.\"}")
            .with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").doesNotExist())
        .andExpect(jsonPath("$.attribution.status").value("COMPUTED"))
        .andExpect(jsonPath("$.attribution.segments[0].share").value(1.0))
        .andExpect(jsonPath("$.counterfactuals.alternatives[0].tier")
            .value("LOCAL"))
        .andExpect(jsonPath("$.counterfactuals.alternatives[0].delta")
            .value(0.40))
        .andExpect(jsonPath("$.provenance.embeddingModelVersion")
            .value("nomic-embed-text"))
        .andExpect(jsonPath("$.provenance.status").value("VALID"));
  }

  @Test
  @DisplayName("explaining a past decision says the prompt is gone, and carries the trace")
  void explainStoredDecision() throws Exception {
    when(useCase.explain("req-1")).thenReturn(Optional.of(storedExplanation()));

    mockMvc.perform(post("/v1/admin/decisions/explain")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"correlationId\":\"req-1\"}")
            .with(authentication(adminAuth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision.routing.chosenTier")
            .value("CLOUD_PREMIUM"))
        .andExpect(jsonPath("$.attribution.status").value("PROMPT_UNAVAILABLE"))
        .andExpect(jsonPath("$.counterfactuals.status")
            .value("PROMPT_UNAVAILABLE"))
        .andExpect(jsonPath("$.carbon.correlationId").value("req-1"));
  }

  @Test
  @DisplayName("both inputs, or neither, is a 400: the caller has not asked one question")
  void explainRequiresExactlyOneInput() throws Exception {
    mockMvc.perform(post("/v1/admin/decisions/explain")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"correlationId\":\"req-1\",\"prompt\":\"hello\"}")
            .with(authentication(adminAuth())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("invalid_explain_request"));

    mockMvc.perform(post("/v1/admin/decisions/explain")
            .contentType(MediaType.APPLICATION_JSON).content("{}")
            .with(authentication(adminAuth())))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("explaining an unknown id is a 404, not an empty explanation")
  void explainReturns404ForUnknownDecision() throws Exception {
    when(useCase.explain(anyString())).thenReturn(Optional.empty());

    mockMvc.perform(post("/v1/admin/decisions/explain")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"correlationId\":\"gone\"}")
            .with(authentication(adminAuth())))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a trace is admin-only: it names entries, routes and other requests")
  void nonAdminIsForbidden() throws Exception {
    ApiKeyAuthentication user = new ApiKeyAuthentication("u", "user");

    mockMvc.perform(get("/v1/admin/decisions").with(authentication(user)))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/v1/admin/decisions/req-1").with(authentication(user)))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/v1/admin/decisions/explain")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"prompt\":\"hello\"}")
            .with(authentication(user)))
        .andExpect(status().isForbidden());
  }

  private static TracedDecision traced() {
    return new TracedDecision("req-1", NOW, cache(), routing());
  }

  private static CacheDecision cache() {
    return new CacheDecision(UUID.randomUUID(), "req-1", NOW, "b".repeat(64),
        CacheOutcome.MISS, 0.71, 0.42, 0.92, null, null, null,
        "nomic-embed-text", ConformalStatus.EMPTY_SET);
  }

  private static RoutingDecision routing() {
    return new RoutingDecision(UUID.randomUUID(), "req-1", NOW, "a".repeat(64),
        42, "nomic-embed-text", "cfg-1", ClassificationStrategy.EMBEDDING,
        ClassificationStrategy.EMBEDDING,
        new ClassificationJustification.Embedding(List.of(), 0.81, 0.12, 0.60),
        DecisionReason.MATCH, ModelTier.CLOUD_PREMIUM, "qwen3:14b", 12L,
        List.of(ModelTier.CLOUD_PREMIUM), 0.05, null);
  }

  private static DecisionExplanation promptExplanation() {
    return new DecisionExplanation(null,
        new AttributionReport(AttributionStatus.COMPUTED, "code",
            ModelTier.CLOUD_PREMIUM, "Refactor this service", 0.81,
            List.of(new SegmentAttribution("Refactor the architecture.", 0.4,
                1.0, 1)),
            "nomic-embed-text", "cfg-1"),
        new CounterfactualReport(CounterfactualStatus.COMPUTED, "code",
            ModelTier.CLOUD_PREMIUM, "Refactor this service", 0.81,
            List.of(new Counterfactual("chat", ModelTier.LOCAL, "Hello there",
                0.41, 0.40, 1)),
            "nomic-embed-text", "cfg-1"),
        new ExplanationProvenance("nomic-embed-text", "cfg-1", NOW,
            CalibrationStatus.VALID));
  }

  private static DecisionExplanation storedExplanation() {
    return new DecisionExplanation(traced(),
        AttributionReport.notComputed(AttributionStatus.PROMPT_UNAVAILABLE,
            "nomic-embed-text", "cfg-1"),
        CounterfactualReport.notComputed(
            CounterfactualStatus.PROMPT_UNAVAILABLE, "nomic-embed-text",
            "cfg-1"),
        new ExplanationProvenance("nomic-embed-text", "cfg-1", NOW,
            CalibrationStatus.VALID));
  }
}
