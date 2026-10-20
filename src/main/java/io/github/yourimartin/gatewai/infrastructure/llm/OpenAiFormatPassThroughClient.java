package io.github.yourimartin.gatewai.infrastructure.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.port.out.PassThroughClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Forwards a pass-through request to the OpenAI-format Chat Completions endpoint
 * of its target's provider instance (ADR 0016, v4 B.3).
 *
 * <p>Plain JDK {@link HttpClient}, not the Spring AI clients: those take a
 * {@code Prompt}, and the point of this path is that the body is never
 * translated — only {@code model} is rewritten. No retry: failover inside a tier
 * is B.5, and it will apply to both paths at once.
 */
@Component
@Profile("!mock")
class OpenAiFormatPassThroughClient implements PassThroughClient {

  private static final Logger LOG = LoggerFactory.getLogger(OpenAiFormatPassThroughClient.class);

  static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
  static final String OLLAMA_BASE_URL = "http://localhost:11434";
  static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";

  /**
   * What Anthropic's OpenAI SDK compatibility layer documents as ignored, or as
   * requiring {@code n = 1} (https://platform.claude.com/docs/en/api/openai-sdk,
   * read 2026-10-20). Forwarding these would answer a different request.
   */
  private static final Set<PassThroughFeature> ANTHROPIC_IGNORED = EnumSet.of(
      PassThroughFeature.RESPONSE_FORMAT, PassThroughFeature.LOGPROBS,
      PassThroughFeature.LOGIT_BIAS, PassThroughFeature.AUDIO_INPUT,
      PassThroughFeature.FILE_INPUT, PassThroughFeature.MULTIPLE_CHOICES);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final int LOGGED_ERROR_CHARS = 512;

  private final Map<String, ProviderProperties.ProviderEntry> providers;
  private final Duration timeout;
  private final HttpClient http;

  @Autowired
  OpenAiFormatPassThroughClient(ProviderProperties providerProperties,
                                @Value("${gatewai.passthrough.timeout:PT5M}") Duration timeout) {
    this(providerProperties, timeout,
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  OpenAiFormatPassThroughClient(ProviderProperties providerProperties, Duration timeout,
                                HttpClient http) {
    Map<String, ProviderProperties.ProviderEntry> byName = new LinkedHashMap<>();
    providerProperties.getProviders()
        .forEach((name, entry) -> byName.put(name.toLowerCase(Locale.ROOT), entry));
    this.providers = byName;
    this.timeout = timeout;
    this.http = http;
  }

  @Override
  public Set<PassThroughFeature> ignoredFeatures(ModelDefinition target) {
    return entry(target).getType() == ProviderProperties.ProviderType.ANTHROPIC
        ? ANTHROPIC_IGNORED : Set.of();
  }

  @Override
  public String describeEndpoint(ModelDefinition target) {
    return entry(target).getType() == ProviderProperties.ProviderType.ANTHROPIC
        ? "Anthropic's OpenAI SDK compatibility layer documents it as ignored"
        : "the OpenAI-format endpoint of provider '" + target.provider() + "'";
  }

  @Override
  public PassThroughResponse forward(ModelDefinition target, String body) {
    ProviderProperties.ProviderEntry entry = entry(target);
    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(entry))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
            PassThroughJson.withModel(body, target.modelId()), StandardCharsets.UTF_8));
    if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
      request.header("Authorization", "Bearer " + entry.getApiKey());
    }

    HttpResponse<String> response = send(request.build(), target);
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      LOG.warn("Pass-through upstream of model '{}' answered HTTP {}: {}", target.modelId(),
          status, truncate(response.body()));
      throw UpstreamFailureException.forStatus(status, target.modelId());
    }
    return new PassThroughResponse(status, response.body(), target.modelId(),
        PassThroughJson.usage(response.body()));
  }

  private HttpResponse<String> send(HttpRequest request, ModelDefinition target) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      LOG.warn("Pass-through upstream of model '{}' did not answer within {}",
          target.modelId(), timeout);
      throw new UpstreamFailureException(UpstreamFailureException.Kind.TIMEOUT, 0,
          "Upstream of model '" + target.modelId() + "' timed out");
    } catch (IOException e) {
      LOG.warn("Pass-through upstream of model '{}' is unreachable: {}", target.modelId(),
          e.toString());
      throw new UpstreamFailureException(UpstreamFailureException.Kind.UNAVAILABLE, 0,
          "Upstream of model '" + target.modelId() + "' is unreachable");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamFailureException(UpstreamFailureException.Kind.UNAVAILABLE, 0,
          "Interrupted while calling the upstream of model '" + target.modelId() + "'");
    }
  }

  /** The OpenAI-format Chat Completions endpoint of an instance (ADR 0016). */
  static URI endpoint(ProviderProperties.ProviderEntry entry) {
    String base = entry.getBaseUrl() == null || entry.getBaseUrl().isBlank()
        ? null : stripTrailingSlash(entry.getBaseUrl().strip());
    String url = switch (entry.getType()) {
      case OPENAI -> (base == null ? OPENAI_BASE_URL : base) + "/chat/completions";
      case OPENAI_COMPATIBLE -> base + "/chat/completions";
      case OLLAMA -> (base == null ? OLLAMA_BASE_URL : base) + "/v1/chat/completions";
      case ANTHROPIC -> (base == null ? ANTHROPIC_BASE_URL : base) + "/v1/chat/completions";
    };
    return URI.create(url);
  }

  private ProviderProperties.ProviderEntry entry(ModelDefinition target) {
    ProviderProperties.ProviderEntry entry =
        providers.get(target.provider().toLowerCase(Locale.ROOT));
    if (entry == null || entry.getType() == null) {
      throw new IllegalStateException("Model '" + target.modelId() + "' references provider '"
          + target.provider() + "', which is not declared with a type.");
    }
    return entry;
  }

  private static String stripTrailingSlash(String url) {
    String stripped = url;
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

  private static String truncate(String body) {
    if (body == null) {
      return "";
    }
    return body.length() <= LOGGED_ERROR_CHARS ? body : body.substring(0, LOGGED_ERROR_CHARS) + "…";
  }
}
