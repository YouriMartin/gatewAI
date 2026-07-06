package io.github.yourimartin.gatewai.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
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

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.LlmMessage;
import io.github.yourimartin.gatewai.domain.model.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.LlmResponse;
import io.github.yourimartin.gatewai.domain.port.in.GetDeferredJobUseCase;
import io.github.yourimartin.gatewai.domain.port.in.SubmitDeferredRequestUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AsyncChatCompletionController.class)
@Import(SecurityConfig.class)
class AsyncChatCompletionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SubmitDeferredRequestUseCase submitUseCase;

  @MockitoBean
  private GetDeferredJobUseCase getUseCase;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  private static final String REQUEST_JSON = """
      {
        "model": "claude-3-sonnet",
        "messages": [{"role": "user", "content": "Hi"}]
      }
      """;

  private static ApiKeyAuthentication auth() {
    return new ApiKeyAuthentication("test-client-id", "test-client");
  }

  @Test
  void submitQueuesJobAndReturns202() throws Exception {
    UUID id = UUID.randomUUID();
    when(submitUseCase.submit(any())).thenReturn(id);

    mockMvc.perform(post("/v1/chat/completions/async")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST_JSON)
            .with(authentication(auth())))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.status").value("queued"));
  }

  @Test
  void getReturnsCompletedJobWithResult() throws Exception {
    UUID id = UUID.randomUUID();
    LlmRequest request = new LlmRequest(
        "claude", List.of(new LlmMessage("user", "Hi")), null, null);
    LlmResponse response = new LlmResponse(
        "claude-haiku", "Hi there!", "stop", 10, 5, 15, false);
    DeferredJob job = DeferredJob.queued(id, request, "tenant", Instant.now())
        .running("SE")
        .completed(response, Instant.now());
    when(getUseCase.find(id)).thenReturn(Optional.of(job));

    mockMvc.perform(get("/v1/chat/completions/async/" + id)
            .with(authentication(auth())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("completed"))
        .andExpect(jsonPath("$.chosen_zone").value("SE"))
        .andExpect(jsonPath("$.result.model").value("claude-haiku"))
        .andExpect(jsonPath("$.result.choices[0].message.content")
            .value("Hi there!"));
  }

  @Test
  void getReturns404ForUnknownJob() throws Exception {
    UUID id = UUID.randomUUID();
    when(getUseCase.find(id)).thenReturn(Optional.empty());

    mockMvc.perform(get("/v1/chat/completions/async/" + id)
            .with(authentication(auth())))
        .andExpect(status().isNotFound());
  }
}
