package io.github.yourimartin.gatewai.adapter.in.web.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.yourimartin.gatewai.adapter.in.web.security.ApiKeyAuthentication;
import io.github.yourimartin.gatewai.adapter.in.web.security.SecurityConfig;
import io.github.yourimartin.gatewai.domain.model.client.ApiClient;
import io.github.yourimartin.gatewai.domain.model.client.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.LlmStreamChunk;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughRequest;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.PassThroughUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(ChatCompletionController.class)
@Import(SecurityConfig.class)
class ChatCompletionControllerTest {

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
        "claude-3-sonnet", "Hello!", "end_turn", 10, 5, 15, false);
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
        "gpt-4", "OK", "stop", 1, 1, 2, false);
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
  void postCarriesTheCacheOutcomeAndTheModelAsHeaders() throws Exception {
    when(useCase.complete(any())).thenReturn(new LlmResponse(
        "qwen2.5:3b", "Cached!", "stop", 10, 5, 15, true, "HIT"));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST_JSON)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isOk())
        .andExpect(header().string(ChatCompletionController.CACHE_HEADER, "HIT"))
        .andExpect(header().string(ChatCompletionController.MODEL_HEADER, "qwen2.5:3b"));
  }

  @Test
  void postWithNoCacheDecisionSendsNoCacheHeader() throws Exception {
    when(useCase.complete(any())).thenReturn(new LlmResponse(
        "gpt-4", "OK", "stop", 1, 1, 2, false));

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST_JSON)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist(ChatCompletionController.CACHE_HEADER));
  }

  @Test
  void postHandsUserAndStopToTheUseCase() throws Exception {
    when(useCase.complete(any())).thenReturn(new LlmResponse(
        "gpt-4", "OK", "stop", 1, 1, 2, false));
    ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "model": "gpt-4",
                  "messages": [{"role": "user", "content": "test"}],
                  "user": "end-user-7",
                  "stop": ["END"]
                }
                """)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isOk());

    verify(useCase).complete(captured.capture());
    assertEquals("end-user-7", captured.getValue().user());
    assertEquals(List.of("END"), captured.getValue().stop());
  }

  @Test
  void postWithStreamTrueReturnsSseChunks() throws Exception {
    doAnswer(invocation -> {
      Consumer<LlmStreamChunk> sink = invocation.getArgument(1);
      sink.accept(new LlmStreamChunk("claude-haiku-4-5", "Hel", "", false, 0, 0, 0, false));
      sink.accept(new LlmStreamChunk("claude-haiku-4-5", "lo", "stop", false, 4, 1, 5, true));
      return null;
    }).when(streamUseCase).streamComplete(any(), any());

    String streamJson = """
        {
          "model": "auto",
          "messages": [{"role": "user", "content": "Hi"}],
          "stream": true
        }
        """;

    MvcResult result = mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(streamJson)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(request().asyncStarted())
        .andReturn();

    mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andExpect(content().string(
            org.hamcrest.Matchers.containsString("chat.completion.chunk")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("[DONE]")));
  }

  /**
   * The stream ends with an async dispatch that runs the security chain again.
   * With a real Bearer key (not the test {@code authentication()} helper, which
   * stores the context where any dispatch finds it), that dispatch used to be
   * denied after the response was committed, so the chunked body was cut short
   * and strict clients (Open WebUI's aiohttp) dropped the stream.
   */
  @Test
  void aStreamAuthenticatedByABearerKeyCompletesItsAsyncDispatch() throws Exception {
    String rawKey = "gw_stream-key";
    when(apiClientRepository.findByApiKeyHash(ApiKeyHasher.hash(rawKey)))
        .thenReturn(Optional.of(new ApiClient(UUID.randomUUID(), "open-webui",
            ApiKeyHasher.hash(rawKey), true, Instant.now(), false)));
    doAnswer(invocation -> {
      Consumer<LlmStreamChunk> sink = invocation.getArgument(1);
      sink.accept(new LlmStreamChunk("qwen2.5:3b", "Hi", "stop", false, 4, 1, 5, true));
      return null;
    }).when(streamUseCase).streamComplete(any(), any());

    MvcResult result = mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + rawKey)
            .content("""
                {"model": "auto", "messages": [{"role": "user", "content": "Hi"}],
                 "stream": true}
                """))
        .andExpect(request().asyncStarted())
        .andReturn();

    mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("[DONE]")));
  }

  @Test
  void postWithoutAuthenticationReturns401() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REQUEST_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aStreamWithIncludeUsageEndsWithAUsageChunk() throws Exception {
    doAnswer(invocation -> {
      Consumer<LlmStreamChunk> sink = invocation.getArgument(1);
      sink.accept(new LlmStreamChunk("qwen2.5:3b", "Hel", "", false, 0, 0, 0, false));
      sink.accept(new LlmStreamChunk("qwen2.5:3b", "lo", "stop", false, 4, 1, 5, true));
      return null;
    }).when(streamUseCase).streamComplete(any(), any());

    MvcResult result = mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model": "auto", "messages": [{"role": "user", "content": "Hi"}],
                 "stream": true, "stream_options": {"include_usage": true}}
                """)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(request().asyncStarted())
        .andReturn();

    String body = mockMvc.perform(asyncDispatch(result))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    List<String> events = body.lines()
        .filter(line -> line.startsWith("data:"))
        .map(line -> line.substring("data:".length()))
        .toList();
    assertEquals("[DONE]", events.getLast());
    String usageChunk = events.get(events.size() - 2);
    assertTrue(usageChunk.contains("\"choices\":[]"), usageChunk);
    assertTrue(usageChunk.contains("\"total_tokens\":5"), usageChunk);
    // Content chunks carry no usage field at all.
    assertFalse(events.getFirst().contains("usage"), events.getFirst());
  }

  @Test
  void aStreamWithoutIncludeUsageSendsNoUsageChunk() throws Exception {
    doAnswer(invocation -> {
      Consumer<LlmStreamChunk> sink = invocation.getArgument(1);
      sink.accept(new LlmStreamChunk("qwen2.5:3b", "Hi", "stop", false, 4, 1, 5, true));
      return null;
    }).when(streamUseCase).streamComplete(any(), any());

    MvcResult result = mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model": "auto", "messages": [{"role": "user", "content": "Hi"}],
                 "stream": true}
                """)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(request().asyncStarted())
        .andReturn();

    mockMvc.perform(asyncDispatch(result))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("usage"))));
  }

  @Test
  void developerRoleAndMaxCompletionTokensReachTheUseCase() throws Exception {
    when(useCase.complete(any())).thenReturn(new LlmResponse(
        "gpt-4", "OK", "stop", 1, 1, 2, false));
    ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"model": "auto", "max_completion_tokens": 300, "seed": 7, "messages": [
                  {"role": "developer", "content": "Be brief."},
                  {"role": "user", "content": [{"type": "text", "text": "Hi"}]}]}
                """)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isOk());

    verify(useCase).complete(captured.capture());
    assertEquals("system", captured.getValue().messages().getFirst().role());
    assertEquals("Hi", captured.getValue().messages().get(1).content());
    assertEquals(300, captured.getValue().maxTokens());
    assertEquals(7L, captured.getValue().sampling().seed());
  }

  private static final String TOOLS_JSON = """
      {"model": "auto", "parallel_tool_calls": false,
       "messages": [{"role": "user", "content": "Weather in Paris?"}],
       "tools": [{"type": "function", "function": {"name": "get_weather"}}]}
      """;

  @Test
  void aMarkedRequestIsForwardedAsWrittenAndItsAnswerRelayed() throws Exception {
    String upstream = "{\"id\":\"chatcmpl-up\",\"object\":\"chat.completion\","
        + "\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\"}],\"x_vendor\":1}";
    when(passThroughUseCase.forward(any())).thenReturn(new PassThroughResponse(
        200, upstream, "qwen2.5:3b", new TokenUsage(5, 3, 8)));
    ArgumentCaptor<PassThroughRequest> captured =
        ArgumentCaptor.forClass(PassThroughRequest.class);

    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(TOOLS_JSON)
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isOk())
        .andExpect(header().string(ChatCompletionController.CACHE_HEADER, "BYPASS"))
        .andExpect(header().string(ChatCompletionController.MODEL_HEADER, "qwen2.5:3b"))
        .andExpect(content().json(upstream, org.springframework.test.json.JsonCompareMode.STRICT));

    verify(passThroughUseCase).forward(captured.capture());
    verifyNoInteractions(useCase, streamUseCase);
    PassThroughRequest forwarded = captured.getValue();
    assertTrue(forwarded.request().passThrough().contains(PassThroughFeature.TOOLS));
    // Unknown fields survive: the body is what the client wrote, not a re-serialised DTO.
    assertTrue(forwarded.body().contains("\"parallel_tool_calls\":false"));
    assertTrue(forwarded.body().contains("\"get_weather\""));
  }

  @Test
  void aMarkedStreamIsRefusedUntilStreamedPassThroughExists() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content(TOOLS_JSON.replace("\"model\": \"auto\",", "\"model\": \"auto\", \"stream\": true,"))
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("unsupported_feature"))
        .andExpect(jsonPath("$.error.param").value("tools"))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.containsString("stream=false")));
    verifyNoInteractions(passThroughUseCase, streamUseCase);
  }

  @Test
  void aBodyThatIsNotTheChatCompletionsShapeIsA400() throws Exception {
    mockMvc.perform(post("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"model\": \"auto\", \"messages\": \"Hi\"}")
            .with(authentication(
                new ApiKeyAuthentication("test-client-id", "test-client"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
        .andExpect(jsonPath("$.error.message")
            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("java"))));
  }
}
