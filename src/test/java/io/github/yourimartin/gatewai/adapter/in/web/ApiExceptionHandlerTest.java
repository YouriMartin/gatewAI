package io.github.yourimartin.gatewai.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
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
}
