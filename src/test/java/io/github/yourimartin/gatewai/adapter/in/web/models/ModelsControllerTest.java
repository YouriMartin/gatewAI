package io.github.yourimartin.gatewai.adapter.in.web.models;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.adapter.in.web.security.ApiKeyAuthentication;
import io.github.yourimartin.gatewai.adapter.in.web.security.SecurityConfig;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.in.ListModelsUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(ModelsController.class)
@Import(SecurityConfig.class)
class ModelsControllerTest {

  private static final ModelDefinition LOCAL =
      new ModelDefinition("local", "ollama", "qwen2.5:3b", 0.0, null, ModelTier.LOCAL);
  private static final ModelDefinition PREMIUM = new ModelDefinition(
      "premium", "anthropic-eu", "claude-sonnet-4-5", 0.015, null, ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition SLASHED = new ModelDefinition(
      "vllm", "vllm", "Qwen/Qwen2.5-7B-Instruct", 0.0, null, ModelTier.CLOUD_ENTRY);

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ListModelsUseCase useCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
    return request.with(authentication(new ApiKeyAuthentication("client-id", "client")));
  }

  @Test
  void listsTheRoutedAliasFirstThenTheRegistryInOrder() throws Exception {
    when(useCase.models()).thenReturn(List.of(LOCAL, PREMIUM));

    mockMvc.perform(authed(get("/v1/models")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.object").value("list"))
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data[0].id").value("auto"))
        .andExpect(jsonPath("$.data[0].object").value("model"))
        .andExpect(jsonPath("$.data[0].owned_by").value("gatewai"))
        .andExpect(jsonPath("$.data[1].id").value("qwen2.5:3b"))
        .andExpect(jsonPath("$.data[1].owned_by").value("ollama"))
        .andExpect(jsonPath("$.data[2].id").value("claude-sonnet-4-5"))
        .andExpect(jsonPath("$.data[2].owned_by").value("anthropic-eu"));
  }

  @Test
  void createdIsAConstantSoItSurvivesARestart() throws Exception {
    when(useCase.models()).thenReturn(List.of(LOCAL));

    mockMvc.perform(authed(get("/v1/models")))
        .andExpect(jsonPath("$.data[0].created").value(0))
        .andExpect(jsonPath("$.data[1].created").value(0));
  }

  @Test
  void retrievesOneRegisteredModel() throws Exception {
    when(useCase.find("qwen2.5:3b")).thenReturn(Optional.of(LOCAL));

    mockMvc.perform(authed(get("/v1/models/qwen2.5:3b")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("qwen2.5:3b"))
        .andExpect(jsonPath("$.object").value("model"))
        .andExpect(jsonPath("$.owned_by").value("ollama"));
  }

  @Test
  void retrievesAModelIdThatContainsASlash() throws Exception {
    when(useCase.find("Qwen/Qwen2.5-7B-Instruct")).thenReturn(Optional.of(SLASHED));

    mockMvc.perform(authed(get("/v1/models/Qwen/Qwen2.5-7B-Instruct")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("Qwen/Qwen2.5-7B-Instruct"));
  }

  @Test
  void retrievesTheRoutedAlias() throws Exception {
    mockMvc.perform(authed(get("/v1/models/auto")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("auto"))
        .andExpect(jsonPath("$.owned_by").value("gatewai"));
  }

  @Test
  void anUnknownModelIsA404InTheOpenAiEnvelope() throws Exception {
    when(useCase.find("gpt-9")).thenReturn(Optional.empty());

    mockMvc.perform(authed(get("/v1/models/gpt-9")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.message").value("The model 'gpt-9' does not exist."))
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.param").value("model"))
        .andExpect(jsonPath("$.error.code").value("model_not_found"));
  }

  @Test
  void requiresTheSameBearerAuthAsTheChatEndpoint() throws Exception {
    mockMvc.perform(get("/v1/models")).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/v1/models/auto")).andExpect(status().isUnauthorized());
  }
}
