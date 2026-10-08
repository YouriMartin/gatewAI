package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.management.PullModelStrategy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What each provider type actually puts on the wire for a gateway prompt (v4 B.1).
 * The providers are built by {@link EgressProviderConfiguration} exactly as in
 * production and pointed at a JDK {@link HttpServer} that records the request
 * body — so these tests catch what a mocked {@code ChatModel} cannot: a provider
 * that casts, drops or renames an option. The JDK server needs no new dependency.
 */
class DelegatingChatModelWireTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private HttpServer server;
  private final Map<String, JsonNode> bodies = new ConcurrentHashMap<>();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> reply(exchange, "openai", """
        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-x",
         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},
                     "finish_reason":"stop"}],
         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
        """));
    server.createContext("/v1/messages", exchange -> reply(exchange, "anthropic", """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-x",
         "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
         "stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}
        """));
    server.createContext("/api/chat", exchange -> reply(exchange, "ollama", """
        {"model":"llama-x","created_at":"2026-10-08T00:00:00Z",
         "message":{"role":"assistant","content":"ok"},"done":true,"done_reason":"stop",
         "total_duration":1,"load_duration":1,"prompt_eval_count":1,
         "prompt_eval_duration":1,"eval_count":1,"eval_duration":1}
        """));
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void openAiCompatibleReceivesEveryForwardedParameter() {
    DelegatingChatModel delegating = delegating(
        ProviderProperties.ProviderType.OPENAI_COMPATIBLE, baseUrl() + "/v1", "gpt-x");

    delegating.call(new Prompt("hello", allOptions("gpt-x")));

    JsonNode body = bodies.get("openai");
    assertThat(body.get("model").asString()).isEqualTo("gpt-x");
    assertThat(body.get("temperature").asDouble()).isEqualTo(0.3);
    assertThat(body.get("top_p").asDouble()).isEqualTo(0.8);
    assertThat(body.get("max_tokens").asInt()).isEqualTo(100);
    // Spring AI sends a single stop sequence in the string form, which the API allows.
    assertThat(body.get("stop").asString()).isEqualTo("END");
    assertThat(body.get("presence_penalty").asDouble()).isEqualTo(0.1);
    assertThat(body.get("frequency_penalty").asDouble()).isEqualTo(0.2);
    assertThat(body.get("seed").asLong()).isEqualTo(42L);
  }

  @Test
  void theOpenAiApiReceivesMaxCompletionTokensInsteadOfTheDeprecatedMaxTokens() {
    DelegatingChatModel delegating = delegating(
        ProviderProperties.ProviderType.OPENAI, baseUrl() + "/v1", "gpt-x");

    delegating.call(new Prompt("hello", allOptions("gpt-x")));

    JsonNode body = bodies.get("openai");
    assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(100);
    assertThat(body.has("max_tokens")).isFalse();
  }

  @Test
  void openAiCompatibleAcceptsPortableOptions() {
    // What the classifier client and the mock-free tests send: no gateway subtype.
    DelegatingChatModel delegating = delegating(
        ProviderProperties.ProviderType.OPENAI_COMPATIBLE, baseUrl() + "/v1", "gpt-x");

    delegating.call(new Prompt("hello", ChatOptions.builder()
        .model("gpt-x").temperature(0.3).build()));

    assertThat(bodies.get("openai").get("temperature").asDouble()).isEqualTo(0.3);
    assertThat(bodies.get("openai").has("seed")).isFalse();
  }

  @Test
  void anthropicReceivesWhatItSupportsAndKeepsItsCredentials() {
    DelegatingChatModel delegating = delegating(
        ProviderProperties.ProviderType.ANTHROPIC, baseUrl(), "claude-x");

    delegating.call(new Prompt("hello", allOptions("claude-x")));

    JsonNode body = bodies.get("anthropic");
    assertThat(body.get("model").asString()).isEqualTo("claude-x");
    assertThat(body.get("max_tokens").asInt()).isEqualTo(100);
    assertThat(body.get("temperature").asDouble()).isEqualTo(0.3);
    assertThat(body.get("top_p").asDouble()).isEqualTo(0.8);
    assertThat(body.get("stop_sequences").get(0).asString()).isEqualTo("END");
    // The Messages API has neither penalties nor a seed.
    assertThat(body.has("seed")).isFalse();
    assertThat(body.has("presence_penalty")).isFalse();
  }

  @Test
  void anthropicKeepsItsDefaultMaxTokensWhenTheClientSendsNone() {
    DelegatingChatModel delegating = delegating(
        ProviderProperties.ProviderType.ANTHROPIC, baseUrl(), "claude-x");

    delegating.call(new Prompt("hello", GatewaiChatOptions.builder().model("claude-x").build()));

    assertThat(bodies.get("anthropic").get("max_tokens").asInt()).isEqualTo(4096);
  }

  @Test
  void ollamaReceivesEveryForwardedParameterAsNativeOptions() {
    DelegatingChatModel delegating = delegating(
        ProviderProperties.ProviderType.OLLAMA, baseUrl(), "llama-x");

    delegating.call(new Prompt("hello", allOptions("llama-x")));

    JsonNode body = bodies.get("ollama");
    assertThat(body.get("model").asString()).isEqualTo("llama-x");
    JsonNode options = body.get("options");
    assertThat(options.get("temperature").asDouble()).isEqualTo(0.3);
    assertThat(options.get("top_p").asDouble()).isEqualTo(0.8);
    assertThat(options.get("num_predict").asInt()).isEqualTo(100);
    assertThat(options.get("stop").get(0).asString()).isEqualTo("END");
    assertThat(options.get("presence_penalty").asDouble()).isEqualTo(0.1);
    assertThat(options.get("frequency_penalty").asDouble()).isEqualTo(0.2);
    assertThat(options.get("seed").asInt()).isEqualTo(42);
  }

  @Test
  void aSeedBeyondThirtyTwoBitsIsNotSent() {
    DelegatingChatModel delegating = delegating(
        ProviderProperties.ProviderType.OPENAI_COMPATIBLE, baseUrl() + "/v1", "gpt-x");

    delegating.call(new Prompt("hello",
        GatewaiChatOptions.builder().model("gpt-x").seed(1L << 40).build()));

    assertThat(bodies.get("openai").has("seed")).isFalse();
  }

  private static ChatOptions allOptions(String modelId) {
    return GatewaiChatOptions.builder()
        .model(modelId)
        .temperature(0.3)
        .topP(0.8)
        .maxTokens(100)
        .stopSequences(List.of("END"))
        .presencePenalty(0.1)
        .frequencyPenalty(0.2)
        .seed(42L)
        .build();
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private void reply(HttpExchange exchange, String name, String body) throws IOException {
    try (InputStream in = exchange.getRequestBody()) {
      bodies.put(name, JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** One instance of {@code type} serving {@code modelId} on the local tier. */
  private static DelegatingChatModel delegating(ProviderProperties.ProviderType type,
                                                String baseUrl, String modelId) {
    ProviderProperties.ProviderEntry entry = new ProviderProperties.ProviderEntry();
    entry.setType(type);
    entry.setApiKey("sk-test");
    entry.setBaseUrl(baseUrl);
    entry.setRegion("eu-west-1");
    // Keep the test offline: never ask the stub to pull a model.
    entry.setPullModelStrategy(PullModelStrategy.NEVER);
    ProviderProperties providers = new ProviderProperties();
    providers.setProviders(new LinkedHashMap<>(Map.of("stub", entry)));

    ModelRegistry registry = registry(modelId);
    ProviderChatModels models = new EgressProviderConfiguration().create(providers, registry,
        ToolCallingManager.builder().build(), ObservationRegistry.NOOP);
    return new DelegatingChatModel(models, registry);
  }

  /** Every tier needs an entry; only the local one is called. */
  private static ModelRegistry registry(String modelId) {
    Map<String, ModelRegistryProperties.ModelEntry> map = new LinkedHashMap<>();
    for (ModelTier tier : ModelTier.values()) {
      ModelRegistryProperties.ModelEntry entry = new ModelRegistryProperties.ModelEntry();
      entry.setProvider("stub");
      entry.setModelId(tier == ModelTier.LOCAL ? modelId : modelId + "-" + tier.name());
      entry.setTier(tier);
      map.put(entry.getModelId(), entry);
    }
    ModelRegistryProperties properties = new ModelRegistryProperties();
    properties.setRegistry(map);
    return new PropertiesModelRegistry(properties);
  }
}
