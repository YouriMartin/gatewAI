package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException.Kind;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.DecisionMetricsRecorder;
import io.github.yourimartin.gatewai.domain.port.out.DecisionRecorder;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.management.PullModelStrategy;

/**
 * Failures as each provider client really reports them (v4 B.5, ADR 0017). The
 * providers are built by {@link EgressProviderConfiguration} as in production and
 * pointed at JDK {@link HttpServer} stubs, so these tests see what a mocked
 * {@code ChatModel} cannot: whether the client retried behind the gateway's back,
 * how it reports a status or a timeout, and whether a real failure fails over.
 */
class EgressFailoverWireTest {

  private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final List<Stub> stubs = new ArrayList<>();

  @AfterEach
  void stop() {
    stubs.forEach(stub -> stub.server.stop(0));
    executor.shutdownNow();
  }

  static Stream<Arguments> transientStatuses() {
    return Stream.of(
        Arguments.of(ProviderProperties.ProviderType.OPENAI_COMPATIBLE, 503, Kind.UNAVAILABLE),
        Arguments.of(ProviderProperties.ProviderType.OPENAI_COMPATIBLE, 429, Kind.RATE_LIMITED),
        Arguments.of(ProviderProperties.ProviderType.ANTHROPIC, 529, Kind.UNAVAILABLE),
        Arguments.of(ProviderProperties.ProviderType.ANTHROPIC, 429, Kind.RATE_LIMITED),
        Arguments.of(ProviderProperties.ProviderType.OLLAMA, 503, Kind.UNAVAILABLE),
        Arguments.of(ProviderProperties.ProviderType.OLLAMA, 429, Kind.RATE_LIMITED));
  }

  @ParameterizedTest
  @MethodSource("transientStatuses")
  void aTransientStatusIsReportedAfterOneRequestWithNoClientRetry(
      ProviderProperties.ProviderType type, int status, Kind kind) throws IOException {
    Stub stub = stub(status);
    DelegatingChatModel delegating = delegating(type, url(type, stub), unreachable(type));

    assertThatThrownBy(() -> delegating.call(pinned("model-1")))
        .isInstanceOfSatisfying(UpstreamFailureException.class, e -> {
          assertThat(e.kind()).isEqualTo(kind);
          assertThat(e.upstreamStatus()).isEqualTo(status);
        });
    assertThat(stub.requests.get()).as("requests the stub received").isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(value = ProviderProperties.ProviderType.class, names = "OPENAI", mode = EnumSource.Mode.EXCLUDE)
  void aBadRequestIsTheClientsToFix(ProviderProperties.ProviderType type) throws IOException {
    Stub stub = stub(400);
    DelegatingChatModel delegating = delegating(type, url(type, stub), unreachable(type));

    assertThatThrownBy(() -> delegating.call(pinned("model-1")))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.kind()).isEqualTo(Kind.REJECTED));
  }

  @ParameterizedTest
  @EnumSource(value = ProviderProperties.ProviderType.class, names = "OPENAI", mode = EnumSource.Mode.EXCLUDE)
  void theReadTimeoutBoundsTheWaitForAnAnswer(ProviderProperties.ProviderType type)
      throws IOException {
    Stub stub = stub(0);
    DelegatingChatModel delegating = delegating(type, url(type, stub), unreachable(type));

    long start = System.nanoTime();
    assertThatThrownBy(() -> delegating.call(pinned("model-1")))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }

  @ParameterizedTest
  @EnumSource(value = ProviderProperties.ProviderType.class, names = "OPENAI", mode = EnumSource.Mode.EXCLUDE)
  void anUnreachableInstanceIsAConnectionFailure(ProviderProperties.ProviderType type)
      throws IOException {
    DelegatingChatModel delegating = delegating(type, unreachable(type), unreachable(type));

    assertThatThrownBy(() -> delegating.call(pinned("model-1")))
        .isInstanceOfSatisfying(UpstreamFailureException.class, e -> {
          assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE);
          assertThat(e.upstreamStatus()).isZero();
        });
  }

  @ParameterizedTest
  @EnumSource(value = ProviderProperties.ProviderType.class, names = "OPENAI", mode = EnumSource.Mode.EXCLUDE)
  void aRoutedCallFailsOverFromAnUnreachableInstanceToTheNextOne(
      ProviderProperties.ProviderType type) throws IOException {
    Stub good = stub(200);
    DelegatingChatModel delegating = delegating(type, unreachable(type), url(type, good));

    ChatResponse response = delegating.call(routed("model-1"));

    assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
    assertThat((String) response.getMetadata()
        .get(DelegatingChatModel.ANSWERED_MODEL_METADATA_KEY)).isEqualTo("model-2");
  }

  @ParameterizedTest
  @EnumSource(value = ProviderProperties.ProviderType.class, names = "OPENAI", mode = EnumSource.Mode.EXCLUDE)
  void aRoutedStreamFailsOverFromAServerErrorToTheNextInstance(
      ProviderProperties.ProviderType type) throws IOException {
    Stub failing = stub(503);
    Stub good = stub(200);
    DelegatingChatModel delegating = delegating(type, url(type, failing), url(type, good));

    List<ChatResponse> chunks = delegating.stream(routed("model-1")).collectList().block();

    assertThat(chunks).extracting(c -> c.getResult() == null ? ""
            : String.valueOf(c.getResult().getOutput().getText()))
        .contains("ok");
    assertThat(failing.requests.get()).isEqualTo(1);
  }

  private static Prompt routed(String modelId) {
    return new Prompt("hello", GatewaiChatOptions.builder().model(modelId).failover(true).build());
  }

  private static Prompt pinned(String modelId) {
    return new Prompt("hello", GatewaiChatOptions.builder().model(modelId).build());
  }

  /**
   * Local tier: model-1 on the first instance, then model-2 on the second. The
   * other tiers, which every registry needs, sit on the second instance.
   */
  private DelegatingChatModel delegating(ProviderProperties.ProviderType type, String first,
                                         String second) {
    ProviderProperties providers = new ProviderProperties();
    providers.setProviders(new LinkedHashMap<>(Map.of(
        "first", instance(type, first), "second", instance(type, second))));

    ModelRegistryProperties properties = new ModelRegistryProperties();
    Map<String, ModelRegistryProperties.ModelEntry> registry = new LinkedHashMap<>();
    registry.put("one", entry("first", "model-1", ModelTier.LOCAL));
    registry.put("two", entry("second", "model-2", ModelTier.LOCAL));
    registry.put("entry", entry("second", "model-entry", ModelTier.CLOUD_ENTRY));
    registry.put("premium", entry("second", "model-premium", ModelTier.CLOUD_PREMIUM));
    properties.setRegistry(registry);
    PropertiesModelRegistry models = new PropertiesModelRegistry(properties);

    ProviderChatModels built = new EgressProviderConfiguration().create(providers, models,
        ToolCallingManager.builder().build(), ObservationRegistry.NOOP);
    return new DelegatingChatModel(built, models,
        new InMemoryEgressHealth(new FailoverProperties()),
        mock(DecisionRecorder.class), mock(DecisionMetricsRecorder.class));
  }

  private static ProviderProperties.ProviderEntry instance(ProviderProperties.ProviderType type,
                                                           String baseUrl) {
    ProviderProperties.ProviderEntry entry = new ProviderProperties.ProviderEntry();
    entry.setType(type);
    entry.setApiKey("sk-test");
    entry.setBaseUrl(baseUrl);
    entry.setRegion("eu-west-1");
    entry.setPullModelStrategy(PullModelStrategy.NEVER);
    entry.setConnectTimeout(Duration.ofSeconds(2));
    entry.setReadTimeout(READ_TIMEOUT);
    return entry;
  }

  private static ModelRegistryProperties.ModelEntry entry(String provider, String modelId,
                                                          ModelTier tier) {
    ModelRegistryProperties.ModelEntry entry = new ModelRegistryProperties.ModelEntry();
    entry.setProvider(provider);
    entry.setModelId(modelId);
    entry.setTier(tier);
    return entry;
  }

  /** The base URL each type expects, for a stub. */
  private static String url(ProviderProperties.ProviderType type, Stub stub) {
    String root = "http://127.0.0.1:" + stub.server.getAddress().getPort();
    return type == ProviderProperties.ProviderType.OPENAI_COMPATIBLE ? root + "/v1" : root;
  }

  /** A port nothing listens on. */
  private static String unreachable(ProviderProperties.ProviderType type) throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    String root = "http://127.0.0.1:" + port;
    return type == ProviderProperties.ProviderType.OPENAI_COMPATIBLE ? root + "/v1" : root;
  }

  /**
   * A stub that answers every provider's endpoint with {@code status}: 200 is a
   * valid answer in the format the request asked for, 0 never answers in time.
   */
  private Stub stub(int status) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    Stub stub = new Stub(server, new AtomicInteger());
    server.createContext("/", exchange -> answer(exchange, stub, status));
    server.setExecutor(executor);
    server.start();
    stubs.add(stub);
    return stub;
  }

  private record Stub(HttpServer server, AtomicInteger requests) {
  }

  private static void answer(HttpExchange exchange, Stub stub, int status) throws IOException {
    stub.requests.incrementAndGet();
    String request;
    try (InputStream in = exchange.getRequestBody()) {
      request = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    if (status == 0) {
      try {
        Thread.sleep(READ_TIMEOUT.multipliedBy(10).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
      return;
    }
    boolean stream = request.replace(" ", "").contains("\"stream\":true");
    String path = exchange.getRequestURI().getPath();
    String body;
    String contentType = "application/json";
    if (status != 200) {
      body = "{\"error\":{\"message\":\"stub " + status + "\",\"type\":\"api_error\"}}";
    } else if (path.endsWith("/chat/completions")) {
      body = stream ? OPENAI_STREAM : OPENAI;
      contentType = stream ? "text/event-stream" : contentType;
    } else if (path.endsWith("/messages")) {
      body = stream ? ANTHROPIC_STREAM : ANTHROPIC;
      contentType = stream ? "text/event-stream" : contentType;
    } else {
      body = stream ? OLLAMA_STREAM : OLLAMA;
      contentType = stream ? "application/x-ndjson" : contentType;
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static final String OPENAI = """
      {"id":"c1","object":"chat.completion","created":1,"model":"m",
       "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                   "finish_reason":"stop"}],
       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
      """;

  private static final String OPENAI_STREAM = """
      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
      "choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}

      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
      "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

      data: [DONE]

      """;

  private static final String ANTHROPIC = """
      {"id":"msg_1","type":"message","role":"assistant","model":"m",
       "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
       "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}
      """;

  private static final String ANTHROPIC_STREAM = """
      event: message_start
      data: {"type":"message_start","message":{"id":"msg_1","type":"message",\
      "role":"assistant","model":"m","content":[],"stop_reason":null,\
      "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":0}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},\
      "usage":{"output_tokens":1}}

      event: message_stop
      data: {"type":"message_stop"}

      """;

  private static final String OLLAMA = """
      {"model":"m","created_at":"2026-10-22T00:00:00Z",
       "message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop",
       "total_duration":1,"load_duration":1,"prompt_eval_count":1,
       "prompt_eval_duration":1,"eval_count":1,"eval_duration":1}
      """;

  private static final String OLLAMA_STREAM = """
      {"model":"m","created_at":"2026-10-22T00:00:00Z",\
      "message":{"role":"assistant","content":"ok"},"done":false}
      {"model":"m","created_at":"2026-10-22T00:00:00Z",\
      "message":{"role":"assistant","content":""},"done":true,"done_reason":"stop",\
      "total_duration":1,"load_duration":1,"prompt_eval_count":1,\
      "prompt_eval_duration":1,"eval_count":1,"eval_duration":1}
      """;
}
