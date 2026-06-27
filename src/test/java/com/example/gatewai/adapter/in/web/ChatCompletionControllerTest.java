package com.example.gatewai.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.gatewai.domain.model.LlmResponse;
import com.example.gatewai.domain.port.in.ChatCompletionUseCase;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ChatCompletionController.class)
@Import(SecurityConfig.class)
class ChatCompletionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ChatCompletionUseCase useCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static final String REQUEST_JSON = """
      {
        "model": "claude-3-sonnet",
        "messages": [
          {"role": "user", "content": "Hi"}
        ],
        "temperature": 0.7,
        "max_tokens": 256
      }
      """;

  @Test
  void postReturnsValidOpenAiResponse() throws Exception {
    LlmResponse llmResponse = new LlmResponse(
        "claude-3-sonnet", "Hello!", "end_turn", 10, 5, 15);
    when(useCase.complete(any())).thenReturn(llmResponse);

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST_JSON)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.startsWith("chatcmpl-")))
        .andExpect(jsonPath("$.object").value("chat.completion"))
        .andExpect(jsonPath("$.created").isNumber())
        .andExpect(jsonPath("$.model").value("claude-3-sonnet"))
        .andExpect(jsonPath("$.choices[0].index").value(0))
        .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
        .andExpect(jsonPath("$.choices[0].message.content").value("Hello!"))
        .andExpect(jsonPath("$.choices[0].finish_reason").value("end_turn"))
        .andExpect(jsonPath("$.usage.prompt_tokens").value(10))
        .andExpect(jsonPath("$.usage.completion_tokens").value(5))
        .andExpect(jsonPath("$.usage.total_tokens").value(15));
  }

  @Test
  void postDeserializesSnakeCaseFields() throws Exception {
    LlmResponse llmResponse = new LlmResponse(
        "gpt-4", "OK", "stop", 1, 1, 2);
    when(useCase.complete(any())).thenReturn(llmResponse);

    String requestJson = """
        {
          "model": "gpt-4",
          "messages": [{"role": "user", "content": "test"}],
          "max_tokens": 100,
          "top_p": 0.9,
          "frequency_penalty": 0.5
        }
        """;

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("gpt-4"));
  }

  @Test
  void postWithoutAuthenticationReturns401() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST_JSON))
        .andExpect(status().isUnauthorized());
  }
}
