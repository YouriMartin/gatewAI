package io.github.yourimartin.gatewai.adapter.in.web.error;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.yourimartin.gatewai.adapter.in.web.chat.ChatCompletionController;
import io.github.yourimartin.gatewai.adapter.in.web.security.ApiKeyAuthentication;
import io.github.yourimartin.gatewai.adapter.in.web.security.SecurityConfig;
import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.model.llm.UnsupportedFeatureException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.PassThroughUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

/** Verifies failures come back in the OpenAI error envelope with the right status. */
@WebMvcTest(ChatCompletionController.class)
@Import(SecurityConfig.class)
class ApiExceptionHandlerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ChatCompletionUseCase useCase;

  @MockitoBean
  private StreamChatCompletionUseCase streamUseCase;

  @MockitoBean
  private PassThroughUseCase passThroughUseCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static final String VALID_JSON = """
      {
        "model": "auto",
        "messages": [{"role": "user", "content": "Hi"}]
      }
      """;

  private static ApiKeyAuthentication auth() {
    return new ApiKeyAuthentication("test-client-id", "test-client");
  }

  @Test
  void missingMessagesReturns400InvalidRequest() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"model\":\"auto\"}")
            .with(authentication(auth())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.containsString("messages")));
  }

  @Test
  void malformedJsonReturns400InvalidRequest() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ not json ")
            .with(authentication(auth())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
  }

  @Test
  void unknownModelReturns400WithUnknownModelCode() throws Exception {
    when(useCase.complete(any()))
        .thenThrow(new UnknownModelException("Unknown model 'mystery'."));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_JSON)
            .with(authentication(auth())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.code").value("unknown_model"))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.containsString("mystery")));
  }

  @Test
  void transientUpstreamErrorReturns503() throws Exception {
    when(useCase.complete(any()))
        .thenThrow(new TransientAiException("provider 429"));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_JSON)
            .with(authentication(auth())))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.type").value("api_error"));
  }

  @Test
  void nonTransientUpstreamErrorReturns502() throws Exception {
    when(useCase.complete(any()))
        .thenThrow(new NonTransientAiException("provider 401 invalid key"));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_JSON)
            .with(authentication(auth())))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.type").value("api_error"))
        // Upstream detail must not leak to the caller.
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("401"))));
  }

  @Test
  void unreachableUpstreamReturns502() throws Exception {
    when(useCase.complete(any()))
        .thenThrow(new ResourceAccessException("connection refused"));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_JSON)
            .with(authentication(auth())))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.type").value("api_error"));
  }

  @Test
  void unexpectedErrorReturns500ApiError() throws Exception {
    when(useCase.complete(any()))
        .thenThrow(new RuntimeException("boom with secret detail"));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_JSON)
            .with(authentication(auth())))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.type").value("api_error"))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret"))));
  }

  private static final String TOOLS_JSON = """
      {"model": "auto",
       "messages": [{"role": "user", "content": "weather in Paris?"}],
       "tools": [{"type": "function", "function": {"name": "get_weather"}}]}
      """;

  @Test
  void aFeatureTheTargetWouldIgnoreIsRefusedWithTheFeatureNamed() throws Exception {
    when(passThroughUseCase.forward(any())).thenThrow(UnsupportedFeatureException.notHonouredBy(
        Set.of(PassThroughFeature.RESPONSE_FORMAT), "claude-opus-4-8",
        "Anthropic's OpenAI SDK compatibility layer documents it as ignored"));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model": "claude-opus-4-8",
                 "messages": [{"role": "user", "content": "Name a colour."}],
                 "response_format": {"type": "json_object"}}
                """)
            .with(authentication(auth())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.code").value("unsupported_feature"))
        .andExpect(jsonPath("$.error.param").value("response_format"))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.containsString("claude-opus-4-8")));
    verifyNoInteractions(useCase);
  }

  @ParameterizedTest
  @CsvSource({
      "REJECTED,      400, 400, invalid_request_error, upstream_rejected",
      "RATE_LIMITED,  429, 429, api_error,             upstream_rate_limited",
      "MISCONFIGURED, 401, 502, api_error,             ",
      "UNAVAILABLE,   503, 502, api_error,             ",
      "TIMEOUT,       0,   504, api_error,             "
  })
  void anUpstreamFailureIsMappedWithoutItsBody(UpstreamFailureException.Kind kind,
                                               int upstreamStatus, int expectedStatus,
                                               String type, String code) throws Exception {
    when(passThroughUseCase.forward(any())).thenThrow(new UpstreamFailureException(kind,
        upstreamStatus, "secret upstream detail sk-proj-123"));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(TOOLS_JSON)
            .with(authentication(auth())))
        .andExpect(status().is(expectedStatus))
        .andExpect(jsonPath("$.error.type").value(type))
        .andExpect(code == null
            ? jsonPath("$.error.code").doesNotExist()
            : jsonPath("$.error.code").value(code))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret"))));
  }

  @Test
  void aStreamedImageIsRefusedBeforeAnyEventIsSent() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model": "auto", "stream": true, "messages": [{"role": "user", "content": [
                  {"type": "text", "text": "What is this?"},
                  {"type": "image_url", "image_url": {"url": "https://example.com/a.png"}}]}]}
                """)
            .with(authentication(auth())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("unsupported_feature"))
        .andExpect(jsonPath("$.error.param").value("messages"))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.containsString("image_url")));
    verifyNoInteractions(streamUseCase);
  }
}
