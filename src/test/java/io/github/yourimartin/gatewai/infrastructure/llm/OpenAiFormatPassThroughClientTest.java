package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenAiFormatPassThroughClientTest {

  private static final ModelDefinition TARGET =
      new ModelDefinition("k", "stub", "served-model", 0.0, null, ModelTier.CLOUD_PREMIUM);

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @CsvSource({
      "OPENAI,            ,                         https://api.openai.com/v1/chat/completions",
      "OPENAI,            https://gw.example/v1/,   https://gw.example/v1/chat/completions",
      "OPENAI_COMPATIBLE, http://vllm:8000/v1,      http://vllm:8000/v1/chat/completions",
      "OLLAMA,            ,                         http://localhost:11434/v1/chat/completions",
      "OLLAMA,            http://gpu:11434,         http://gpu:11434/v1/chat/completions",
      "ANTHROPIC,         ,                         https://api.anthropic.com/v1/chat/completions",
      "ANTHROPIC,         https://proxy.example,    https://proxy.example/v1/chat/completions"
  })
  void eachProviderTypeHasItsOpenAiFormatEndpoint(ProviderProperties.ProviderType type,
                                                  String baseUrl, String expected) {
    assertThat(OpenAiFormatPassThroughClient.endpoint(entry(type, baseUrl, null)))
        .hasToString(expected);
  }

  @Test
  void anthropicDeclaresWhatItsCompatibilityLayerIgnores() {
    OpenAiFormatPassThroughClient client = client(ProviderProperties.ProviderType.ANTHROPIC,
        null, "sk-ant-api03-x");

    assertThat(client.ignoredFeatures(TARGET)).containsExactlyInAnyOrder(
        PassThroughFeature.RESPONSE_FORMAT, PassThroughFeature.LOGPROBS,
        PassThroughFeature.LOGIT_BIAS, PassThroughFeature.AUDIO_INPUT,
        PassThroughFeature.FILE_INPUT, PassThroughFeature.MULTIPLE_CHOICES);
    assertThat(client.ignoredFeatures(TARGET)).doesNotContain(
        PassThroughFeature.TOOLS, PassThroughFeature.TOOL_MESSAGES, PassThroughFeature.IMAGE_INPUT);
    assertThat(client.describeEndpoint(TARGET)).contains("compatibility layer");
  }

  @Test
  void anOpenAiCompatibleServerIsTheAuthorityOnItsOwnFeatures() {
    assertThat(client(ProviderProperties.ProviderType.OPENAI_COMPATIBLE, "http://x/v1", null)
        .ignoredFeatures(TARGET)).isEmpty();
  }

  @Test
  void forwardsTheBodyWithOnlyTheModelRewrittenAndReadsTheUsage() throws IOException {
    AtomicReference<String> received = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    String answer = "{\"id\":\"x\",\"choices\":[],\"usage\":"
        + "{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":7}}";
    start(200, answer, received, auth);

    PassThroughResponse response = client(ProviderProperties.ProviderType.OPENAI_COMPATIBLE,
        base(), "sk-secret").forward(TARGET,
        "{\"model\":\"auto\",\"messages\":[],\"vendor_flag\":{\"a\":1}}");

    assertThat(received.get())
        .isEqualTo("{\"model\":\"served-model\",\"messages\":[],\"vendor_flag\":{\"a\":1}}");
    assertThat(auth.get()).isEqualTo("Bearer sk-secret");
    assertThat(response.status()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(answer);
    assertThat(response.model()).isEqualTo("served-model");
    // The provider's own total is kept, not recomputed.
    assertThat(response.usage()).isEqualTo(new TokenUsage(4, 2, 7));
  }

  @Test
  void noKeyMeansNoAuthorizationHeader() throws IOException {
    AtomicReference<String> auth = new AtomicReference<>("unset");
    start(200, "{}", new AtomicReference<>(), auth);

    PassThroughResponse response = client(ProviderProperties.ProviderType.OPENAI_COMPATIBLE,
        base(), null).forward(TARGET, "{\"model\":\"x\"}");

    assertThat(auth.get()).isNull();
    assertThat(response.usage()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
      "400, REJECTED", "422, REJECTED", "413, REJECTED", "429, RATE_LIMITED",
      "401, MISCONFIGURED", "403, MISCONFIGURED", "404, MISCONFIGURED",
      "500, UNAVAILABLE", "503, UNAVAILABLE"
  })
  void anErrorStatusIsClassifiedAndItsBodyKept(int status, UpstreamFailureException.Kind kind)
      throws IOException {
    start(status, "{\"error\":{\"message\":\"internal-host:9000\"}}", new AtomicReference<>(),
        new AtomicReference<>());

    assertThatThrownBy(() -> client(ProviderProperties.ProviderType.OPENAI_COMPATIBLE, base(),
        null).forward(TARGET, "{}"))
        .isInstanceOfSatisfying(UpstreamFailureException.class, e -> {
          assertThat(e.kind()).isEqualTo(kind);
          assertThat(e.upstreamStatus()).isEqualTo(status);
          assertThat(e.getMessage()).doesNotContain("internal-host");
        });
  }

  @Test
  void anUnreachableUpstreamIsUnavailable() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }

    assertThatThrownBy(() -> client(ProviderProperties.ProviderType.OPENAI_COMPATIBLE,
        "http://localhost:" + closedPort + "/v1", null).forward(TARGET, "{}"))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.kind()).isEqualTo(UpstreamFailureException.Kind.UNAVAILABLE));
  }

  @Test
  void aSlowUpstreamTimesOut() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
    });
    server.start();
    OpenAiFormatPassThroughClient client = new OpenAiFormatPassThroughClient(
        providers(entry(ProviderProperties.ProviderType.OPENAI_COMPATIBLE, base(), null)),
        Duration.ofMillis(200));

    assertThatThrownBy(() -> client.forward(TARGET, "{}"))
        .isInstanceOfSatisfying(UpstreamFailureException.class,
            e -> assertThat(e.kind()).isEqualTo(UpstreamFailureException.Kind.TIMEOUT));
  }

  private void start(int status, String body, AtomicReference<String> received,
                     AtomicReference<String> auth) throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
  }

  private String base() {
    return "http://localhost:" + server.getAddress().getPort() + "/v1";
  }

  private static OpenAiFormatPassThroughClient client(ProviderProperties.ProviderType type,
                                                      String baseUrl, String key) {
    return new OpenAiFormatPassThroughClient(providers(entry(type, baseUrl, key)),
        Duration.ofSeconds(5));
  }

  private static ProviderProperties providers(ProviderProperties.ProviderEntry entry) {
    ProviderProperties properties = new ProviderProperties();
    properties.setProviders(new java.util.LinkedHashMap<>(Map.of("Stub", entry)));
    return properties;
  }

  private static ProviderProperties.ProviderEntry entry(ProviderProperties.ProviderType type,
                                                        String baseUrl, String key) {
    ProviderProperties.ProviderEntry entry = new ProviderProperties.ProviderEntry();
    entry.setType(type);
    entry.setBaseUrl(baseUrl);
    entry.setApiKey(key);
    return entry;
  }
}
