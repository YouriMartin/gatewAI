package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.yourimartin.gatewai.adapter.in.web.chat.ChatCompletionController;
import io.github.yourimartin.gatewai.adapter.in.web.security.ApiKeyAuthentication;
import io.github.yourimartin.gatewai.adapter.in.web.security.SecurityConfig;
import io.github.yourimartin.gatewai.domain.model.carbon.CarbonCalculator;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecisionReason;
import io.github.yourimartin.gatewai.domain.model.decision.CacheOutcome;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;
import io.github.yourimartin.gatewai.domain.model.routing.DecisionReason;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * B.3 acceptance (ADR 0016): the ingress, the real pass-through service and the
 * real HTTP adapter, against a JDK {@link HttpServer} playing an OpenAI-format
 * upstream. Each request is a client fixture; each lands in {@code request_log}
 * with the upstream's usage, with a {@code BYPASS} cache decision that says why
 * and a pass-through routing decision. The advisor chain is never called.
 */
@WebMvcTest(ChatCompletionController.class)
@Import({SecurityConfig.class, PassThroughAcceptanceTest.Wiring.class})
class PassThroughAcceptanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MODEL = "stub-premium";
  private static final String KEY = "sk-stub-provider-key";

  private static final HttpServer UPSTREAM = startUpstream();
  private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
  private static final AtomicInteger NEXT_STATUS = new AtomicInteger(200);

  /** One request the stub upstream received. */
  private record Received(String authorization, JsonNode body) {
  }

  @TestConfiguration
  @ComponentScan(basePackages = "io.github.yourimartin.gatewai.application.service",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
          pattern = ".*\\.PassThroughService"))
  @Import({OpenAiFormatPassThroughClient.class, PropertiesModelRegistry.class})
  @EnableConfigurationProperties({ProviderProperties.class, ModelRegistryProperties.class})
  static class Wiring {

    @Bean
    GreenAccountant greenAccountant() {
      return new GreenAccountant(new CarbonCalculator());
    }
  }

  @DynamicPropertySource
  static void upstream(DynamicPropertyRegistry registry) {
    String base = "http://localhost:" + UPSTREAM.getAddress().getPort() + "/v1";
    registry.add("gatewai.providers.stub.type", () -> "openai-compatible");
    registry.add("gatewai.providers.stub.base-url", () -> base);
    registry.add("gatewai.providers.stub.api-key", () -> KEY);
    registry.add("gatewai.models.registry.premium.provider", () -> "stub");
    registry.add("gatewai.models.registry.premium.model-id", () -> MODEL);
    registry.add("gatewai.models.registry.premium.cost-per-1k-tokens", () -> "0.01");
    registry.add("gatewai.models.registry.premium.tier", () -> "cloud-premium");
  }

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ChatCompletionUseCase chain;
  @MockitoBean
  private StreamChatCompletionUseCase streamChain;
  @MockitoBean
  private ApiClientRepository apiClientRepository;
  @MockitoBean
  private RequestLogRepository requestLogRepository;
  @MockitoBean
  private DecisionRecorder decisionRecorder;
  @MockitoBean
  private DecisionMetricsRecorder decisionMetrics;
  @MockitoBean
  private MetricsRecorder metricsRecorder;
  @MockitoBean
  private CarbonIntensityProvider carbonIntensityProvider;
  @MockitoBean
  private ProviderRegions providerRegions;
  @MockitoBean
  private CloudRegionZones cloudRegionZones;

  @BeforeEach
  void reset() {
    RECEIVED.clear();
    NEXT_STATUS.set(200);
  }

  @AfterAll
  static void stopUpstream() {
    UPSTREAM.stop(0);
  }

  @Test
  void aToolCallingRoundTripIsForwardedTracedAndAccounted() throws Exception {
    // Turn 1: tools declared, no tool result yet → the upstream asks for a call.
    send(fixture("openai-tools-first-turn.json"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-GatewAI-Cache", "BYPASS"))
        .andExpect(header().string("X-GatewAI-Model", MODEL))
        .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
        .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name")
            .value("get_weather"));

    // Turn 2: the client ran the tool and sends its result → the final answer.
    send(fixture("langchain-tool-calling-turn.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.choices[0].message.content").value("It is 18 degrees in Paris."));

    assertThat(RECEIVED).hasSize(2);
    JsonNode first = RECEIVED.get(0).body();
    assertThat(first.path("model").asString()).isEqualTo(MODEL);
    // Everything else is the client's body, fields the gateway does not model included.
    assertThat(first.path("parallel_tool_calls").asBoolean(true)).isFalse();
    assertThat(first.path("tools").get(0).path("function").path("strict").asBoolean()).isTrue();
    assertThat(RECEIVED.get(0).authorization()).isEqualTo("Bearer " + KEY);
    assertThat(RECEIVED.get(1).body().path("messages").get(2).path("tool_call_id").asString())
        .isEqualTo("call_abc123");

    List<RequestLog> logs = requestLogs(2);
    assertThat(logs).allSatisfy(log -> {
      assertThat(log.model()).isEqualTo(MODEL);
      assertThat(log.cacheHit()).isFalse();
    });
    assertThat(logs.get(0).promptTokens()).isEqualTo(21);
    assertThat(logs.get(0).completionTokens()).isEqualTo(7);
    assertThat(logs.get(0).totalTokens()).isEqualTo(28);

    assertThat(cacheDecisions()).extracting(CacheDecision::reason).containsExactly(
        CacheDecisionReason.PASSTHROUGH_TOOLS, CacheDecisionReason.PASSTHROUGH_TOOLS);
    verifyNoInteractions(chain, streamChain);
  }

  @Test
  void aJsonSchemaRequestIsForwardedTracedAndAccounted() throws Exception {
    send(fixture("openai-response-format-json-schema.json"))
        .andExpect(status().isOk());

    assertThat(RECEIVED.getFirst().body().path("response_format").path("json_schema")
        .path("name").asString()).isEqualTo("calendar_event");
    assertThat(requestLogs(1).getFirst().totalTokens()).isEqualTo(28);
    assertThat(cacheDecisions()).extracting(CacheDecision::reason)
        .containsExactly(CacheDecisionReason.PASSTHROUGH_RESPONSE_FORMAT);

    ArgumentCaptor<RoutingDecision> routing = ArgumentCaptor.forClass(RoutingDecision.class);
    verify(decisionRecorder).record(routing.capture());
    assertThat(routing.getValue().decisionReason()).isEqualTo(DecisionReason.PASSTHROUGH_DEFAULT);
    assertThat(routing.getValue().chosenModelId()).isEqualTo(MODEL);
    assertThat(routing.getValue().chosenTier()).isEqualTo(ModelTier.CLOUD_PREMIUM);
    assertThat(routing.getValue().justification()).isNull();
    verifyNoInteractions(chain, streamChain);
  }

  @Test
  void anImagePartIsForwardedTracedAndAccounted() throws Exception {
    ObjectNode body = fixture("chat-ui-image.json");
    body.put("stream", false); // streamed pass-through is B.4
    body.put("model", MODEL);

    send(body).andExpect(status().isOk());

    assertThat(RECEIVED.getFirst().body().path("messages").get(0).path("content").get(1)
        .path("image_url").path("url").asString()).startsWith("data:image/png;base64,");
    assertThat(requestLogs(1)).hasSize(1);
    assertThat(cacheDecisions()).singleElement().satisfies(decision -> {
      assertThat(decision.outcome()).isEqualTo(CacheOutcome.BYPASS);
      assertThat(decision.reason()).isEqualTo(CacheDecisionReason.PASSTHROUGH_IMAGE_INPUT);
    });
    ArgumentCaptor<RoutingDecision> routing = ArgumentCaptor.forClass(RoutingDecision.class);
    verify(decisionRecorder).record(routing.capture());
    assertThat(routing.getValue().decisionReason()).isEqualTo(DecisionReason.PASSTHROUGH_PINNED);
  }

  @Test
  void anUpstreamErrorComesBackInTheEnvelopeWithoutTheUpstreamBody() throws Exception {
    NEXT_STATUS.set(500);

    send(fixture("openai-tools-first-turn.json"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.type").value("api_error"))
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal-host"))));

    verifyNoInteractions(requestLogRepository);
  }

  @Test
  void anUpstreamRejectionNamesTheStatusAsTheClientsToFix() throws Exception {
    NEXT_STATUS.set(422);

    send(fixture("openai-tools-first-turn.json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("upstream_rejected"))
        .andExpect(jsonPath("$.error.message").value(
            org.hamcrest.Matchers.containsString("HTTP 422")));
  }

  private org.springframework.test.web.servlet.ResultActions send(JsonNode body)
      throws Exception {
    return mockMvc.perform(post("/v1/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(body))
        .with(authentication(new ApiKeyAuthentication("client-1", "test-client"))));
  }

  private List<RequestLog> requestLogs(int expected) {
    ArgumentCaptor<RequestLog> logs = ArgumentCaptor.forClass(RequestLog.class);
    verify(requestLogRepository, atLeastOnce()).save(logs.capture());
    assertThat(logs.getAllValues()).hasSize(expected);
    return logs.getAllValues();
  }

  private List<CacheDecision> cacheDecisions() {
    ArgumentCaptor<CacheDecision> decisions = ArgumentCaptor.forClass(CacheDecision.class);
    verify(decisionRecorder, atLeastOnce()).record(decisions.capture());
    return decisions.getAllValues();
  }

  private static ObjectNode fixture(String name) throws IOException {
    try (InputStream in = PassThroughAcceptanceTest.class.getResourceAsStream(
        "/fixtures/openai-requests/" + name)) {
      return (ObjectNode) JSON.readTree(in);
    }
  }

  /**
   * An OpenAI-format upstream, scripted: a tool call while the last message is
   * not a tool result, then a final answer; {@code NEXT_STATUS} other than 200
   * answers with an error body that must never reach the client.
   */
  private static HttpServer startUpstream() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/v1/chat/completions", PassThroughAcceptanceTest::answer);
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void answer(HttpExchange exchange) throws IOException {
    JsonNode body = JSON.readTree(exchange.getRequestBody());
    RECEIVED.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"), body));
    int status = NEXT_STATUS.get();
    String reply;
    if (status != 200) {
      reply = "{\"error\":{\"message\":\"model backend internal-host:9000 exploded\"}}";
    } else {
      JsonNode messages = body.path("messages");
      boolean toolResult = "tool".equals(
          messages.get(messages.size() - 1).path("role").asString(""));
      String message = body.has("tools") && !toolResult
          ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_abc123\","
              + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
              + "\"arguments\":\"{\\\"city\\\":\\\"Paris\\\"}\"}}]}"
          : "{\"role\":\"assistant\",\"content\":\"It is 18 degrees in Paris.\"}";
      String finish = body.has("tools") && !toolResult ? "tool_calls" : "stop";
      reply = "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1,"
          + "\"model\":\"" + body.path("model").asString() + "-2026-01-01\",\"choices\":[{"
          + "\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finish + "\"}],"
          + "\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":7,\"total_tokens\":28}}";
    }
    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
