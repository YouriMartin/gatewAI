package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.yourimartin.gatewai.adapter.in.web.chat.ChatCompletionController;
import io.github.yourimartin.gatewai.adapter.in.web.security.SecurityConfig;
import io.github.yourimartin.gatewai.domain.model.carbon.CarbonCalculator;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenAccountant;
import io.github.yourimartin.gatewai.domain.model.client.ApiClient;
import io.github.yourimartin.gatewai.domain.model.client.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.model.decision.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.decision.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.decision.ShadowOutcome;
import io.github.yourimartin.gatewai.domain.model.llm.LlmRequest;
import io.github.yourimartin.gatewai.domain.model.llm.LlmResponse;
import io.github.yourimartin.gatewai.domain.model.llm.RelayedAnswer;
import io.github.yourimartin.gatewai.domain.model.shadow.JudgeOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.WouldHitAssessment;
import io.github.yourimartin.gatewai.domain.port.in.ChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.in.StreamChatCompletionUseCase;
import io.github.yourimartin.gatewai.domain.port.out.AnswerJudge;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;
import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;
import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.github.yourimartin.gatewai.domain.port.out.EmbeddingWindow;
import io.github.yourimartin.gatewai.domain.port.out.LlmClient;
import io.github.yourimartin.gatewai.domain.port.out.MetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;
import io.github.yourimartin.gatewai.domain.port.out.TextEmbedder;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D.1 acceptance: a shadow client, authenticated by a real key, against the real
 * ingress, pass-through service, shadow evaluator and HTTP adapter, with a JDK
 * {@link HttpServer} playing the upstream. The advisor chain is the mocked
 * {@link LlmClient}: its replay answers as a cache holding a would-hit would.
 * The client still receives the upstream's bytes, the chain decides on the answer
 * it received, and an {@code ENFORCE} client is served as before.
 */
@WebMvcTest(ChatCompletionController.class)
@Import({SecurityConfig.class, ShadowModeAcceptanceTest.Wiring.class})
class ShadowModeAcceptanceTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String MODEL = "stub-premium";
  private static final String SHADOW_KEY = "gw_shadow-key";
  private static final String ENFORCE_KEY = "gw_enforce-key";
  private static final String UPSTREAM_TEXT = "It is 18 degrees in Paris.";
  private static final String CACHED_TEXT = "A cached answer the client must never see.";

  private static final HttpServer UPSTREAM = startUpstream();
  private static final List<JsonNode> RECEIVED = new CopyOnWriteArrayList<>();
  private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();

  @TestConfiguration
  @ComponentScan(basePackages = "io.github.yourimartin.gatewai.application.service",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
          pattern = ".*\\.(PassThroughService|ShadowEvaluator|WouldHitAssessor)"))
  @Import({OpenAiFormatPassThroughClient.class, OpenAiFormatAnswerReader.class,
      PropertiesModelRegistry.class, InMemoryEgressHealth.class})
  @EnableConfigurationProperties({ProviderProperties.class, ModelRegistryProperties.class,
      FailoverProperties.class})
  static class Wiring {

    @Bean
    GreenAccountant greenAccountant() {
      return new GreenAccountant(new CarbonCalculator(), "EUR");
    }
  }

  @DynamicPropertySource
  static void upstream(DynamicPropertyRegistry registry) {
    String base = "http://localhost:" + UPSTREAM.getAddress().getPort() + "/v1";
    registry.add("gatewai.providers.stub.type", () -> "openai-compatible");
    registry.add("gatewai.providers.stub.base-url", () -> base);
    registry.add("gatewai.providers.stub.api-key", () -> "sk-stub");
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
  private LlmClient llmClient;
  @MockitoBean
  private TextEmbedder textEmbedder;
  @MockitoBean
  private EmbeddingWindow embeddingWindow;
  @MockitoBean
  private AnswerJudge answerJudge;
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

  /** The context the replay ran under, as the advisors would have read it. */
  private final AtomicReference<RequestContext> replayContext = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    RECEIVED.clear();
    LAST_BODY.set(null);
    client(SHADOW_KEY, "shadow-app", ClientMode.SHADOW);
    client(ENFORCE_KEY, "enforce-app", ClientMode.ENFORCE);
    when(textEmbedder.embed(any())).thenReturn(new float[] {1, 0});
    // The chain's verdict: the cache holds a would-hit for this question.
    when(llmClient.replay(any(), any())).thenAnswer(invocation -> {
      replayContext.set(RequestContext.CURRENT.get());
      return new LlmResponse(MODEL, CACHED_TEXT, "stop", 0, 0, 0, true, "HIT", null);
    });
  }

  @AfterAll
  static void stopUpstream() {
    UPSTREAM.stop(0);
  }

  @Test
  void aShadowClientReceivesTheUpstreamBytesEvenWhenTheCacheHoldsAWouldHit() throws Exception {
    String received = send(SHADOW_KEY, request("auto", false))
        .andExpect(status().isOk())
        .andExpect(header().string("X-GatewAI-Mode", "SHADOW"))
        .andExpect(header().string("X-GatewAI-Cache", "BYPASS"))
        .andExpect(header().string("X-GatewAI-Model", MODEL))
        .andReturn().getResponse().getContentAsString();

    assertThat(received).isEqualTo(LAST_BODY.get());
    assertThat(received).contains(UPSTREAM_TEXT).doesNotContain(CACHED_TEXT);
    // Sent as the client wrote it, to the default model.
    assertThat(RECEIVED).hasSize(1);
    assertThat(RECEIVED.getFirst().path("model").asString()).isEqualTo(MODEL);

    // The chain decided on the answer the client received, under a SHADOW context.
    ArgumentCaptor<RelayedAnswer> answer = ArgumentCaptor.forClass(RelayedAnswer.class);
    ArgumentCaptor<LlmRequest> replayed = ArgumentCaptor.forClass(LlmRequest.class);
    verify(llmClient, timeout(5_000)).replay(replayed.capture(), answer.capture());
    assertThat(answer.getValue().text()).isEqualTo(UPSTREAM_TEXT);
    assertThat(answer.getValue().finishReason()).isEqualTo("stop");
    assertThat(answer.getValue().model()).isEqualTo(MODEL);
    assertThat(replayed.getValue().model()).isEqualTo("auto");
    assertThat(replayContext.get().mode()).isEqualTo(ClientMode.SHADOW);
    verify(metricsRecorder, timeout(5_000)).recordShadowDecision(ShadowOutcome.WOULD_HIT);

    // D.2: the would-hit is compared with what the client received, by similarity only
    // since the judge is off by default — and nothing but numbers is recorded.
    ArgumentCaptor<WouldHitAssessment> assessed = ArgumentCaptor.forClass(WouldHitAssessment.class);
    verify(decisionRecorder, timeout(5_000)).record(assessed.capture());
    assertThat(assessed.getValue().correlationId()).isEqualTo(replayContext.get().traceId());
    assertThat(assessed.getValue().answerSimilarity()).isEqualTo(1.0);
    assertThat(assessed.getValue().judgeOutcome()).isEqualTo(JudgeOutcome.NOT_SAMPLED);
    verify(textEmbedder).embed(CACHED_TEXT);
    verify(textEmbedder).embed(UPSTREAM_TEXT);
    verifyNoInteractions(answerJudge);

    // Served and accounted as a pass-through; the verdict is the chain's, not BYPASS.
    verify(requestLogRepository).save(any());
    verify(decisionRecorder, never()).record(any(CacheDecision.class));
    verify(decisionRecorder, never()).record(any(RoutingDecision.class));
    verifyNoInteractions(chain, streamChain);
  }

  @Test
  void aStreamedShadowAnswerIsRelayedUnchangedAndReplayedWhole() throws Exception {
    MvcResult started = send(SHADOW_KEY, request(MODEL, true))
        .andExpect(header().string("X-GatewAI-Mode", "SHADOW"))
        .andReturn();
    String received = mockMvc.perform(asyncDispatch(started))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(received).contains("It is 18").contains(" degrees in Paris.")
        .doesNotContain(CACHED_TEXT).endsWith("data:[DONE]\n\n");
    ArgumentCaptor<RelayedAnswer> answer = ArgumentCaptor.forClass(RelayedAnswer.class);
    verify(llmClient, timeout(5_000)).replay(any(), answer.capture());
    assertThat(answer.getValue().text()).isEqualTo(UPSTREAM_TEXT);
    assertThat(answer.getValue().usage().totalTokens()).isEqualTo(28);
  }

  /**
   * The served request does not wait for the evaluation: with the replay blocked,
   * the client has its answer. The latency shadow adds to the request is the
   * submission alone — a permit and a virtual-thread start.
   */
  @Test
  void theClientDoesNotWaitForTheShadowEvaluation() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
      release.await(10, TimeUnit.SECONDS);
      return new LlmResponse(MODEL, CACHED_TEXT, "stop", 0, 0, 0, true, "HIT", null);
    }).when(llmClient).replay(any(), any());
    try {
      send(SHADOW_KEY, request("auto", false))
          .andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString(UPSTREAM_TEXT)));
      verify(metricsRecorder, never()).recordShadowDecision(ShadowOutcome.WOULD_HIT);
    } finally {
      release.countDown();
    }
    verify(metricsRecorder, timeout(5_000)).recordShadowDecision(ShadowOutcome.WOULD_HIT);
  }

  @Test
  void aShadowClientNamingAnUnregisteredModelIsRefusedNotGuessed() throws Exception {
    send(SHADOW_KEY, request("gpt-unknown", false))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("unknown_model"));
    assertThat(RECEIVED).isEmpty();
    verifyNoInteractions(llmClient);
  }

  @Test
  void anEnforceClientIsServedByTheChainAsBefore() throws Exception {
    when(chain.complete(any())).thenReturn(
        new LlmResponse(MODEL, "From the chain.", "stop", 1, 1, 2, false, "MISS", null));

    send(ENFORCE_KEY, request("auto", false))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("X-GatewAI-Mode"))
        .andExpect(header().string("X-GatewAI-Cache", "MISS"))
        .andExpect(jsonPath("$.choices[0].message.content").value("From the chain."));

    assertThat(RECEIVED).isEmpty();
    verifyNoInteractions(llmClient);
  }

  private void client(String rawKey, String name, ClientMode mode) {
    ApiClient client = new ApiClient(UUID.randomUUID(), name, ApiKeyHasher.hash(rawKey), true,
        Instant.now(), false).withMode(mode, "admin-1", Instant.now());
    when(apiClientRepository.findByApiKeyHash(ApiKeyHasher.hash(rawKey)))
        .thenReturn(Optional.of(client));
  }

  private static String request(String model, boolean stream) {
    return "{\"model\":\"" + model + "\",\"stream\":" + stream
        + ",\"messages\":[{\"role\":\"user\",\"content\":\"Weather in Paris?\"}]}";
  }

  private ResultActions send(String rawKey, String body) throws Exception {
    return mockMvc.perform(post("/v1/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + rawKey)
        .content(body));
  }

  private static HttpServer startUpstream() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/v1/chat/completions", ShadowModeAcceptanceTest::answer);
      server.start();
      return server;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void answer(HttpExchange exchange) throws IOException {
    JsonNode body = JSON.readTree(exchange.getRequestBody());
    RECEIVED.add(body);
    if (body.path("stream").asBoolean(false)) {
      stream(exchange);
      return;
    }
    String reply = "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion\",\"created\":1,"
        + "\"model\":\"stub-premium-2026-01-01\",\"system_fingerprint\":\"fp_stub\","
        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
        + UPSTREAM_TEXT + "\"},\"finish_reason\":\"stop\"}],"
        + "\"usage\":{\"prompt_tokens\":21,\"completion_tokens\":7,\"total_tokens\":28}}";
    LAST_BODY.set(reply);
    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void stream(HttpExchange exchange) throws IOException {
    String head = "{\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\","
        + "\"created\":1,\"model\":\"stub-premium-2026-01-01\",";
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      sse(out, head + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},"
          + "\"finish_reason\":null}],\"usage\":null}");
      sse(out, head + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"It is 18\"},"
          + "\"finish_reason\":null}],\"usage\":null}");
      sse(out, head + "\"choices\":[{\"index\":0,\"delta\":{\"content\":"
          + "\" degrees in Paris.\"},\"finish_reason\":null}],\"usage\":null}");
      sse(out, head + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
          + "\"usage\":null}");
      sse(out, head + "\"choices\":[],\"usage\":{\"prompt_tokens\":21,"
          + "\"completion_tokens\":7,\"total_tokens\":28}}");
      sse(out, "[DONE]");
    }
  }

  private static void sse(OutputStream out, String data) throws IOException {
    out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }
}
