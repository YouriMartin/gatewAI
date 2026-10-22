package io.github.yourimartin.gatewai.infrastructure.llm;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.Function;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.port.out.PassThroughClient;
import io.github.yourimartin.gatewai.domain.port.out.UpstreamEventStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Forwards a pass-through request to the OpenAI-format Chat Completions endpoint
 * of its target's provider instance (ADR 0016, v4 B.3).
 *
 * <p>Plain JDK {@link HttpClient}, not the Spring AI clients: those take a
 * {@code Prompt}, and the point of this path is that the body is never
 * translated — only {@code model} is rewritten. No retry: failover inside a tier
 * is the service's (ADR 0017), and each instance's {@code connect-timeout} and
 * {@code read-timeout} bound a call here as they do on the chain.
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

  private static final int LOGGED_ERROR_CHARS = 512;

  private final Map<String, ProviderProperties.ProviderEntry> providers;
  /** One client per instance: the JDK sets the connect timeout per client. */
  private final Map<String, HttpClient> clients = new LinkedHashMap<>();

  @Autowired
  OpenAiFormatPassThroughClient(ProviderProperties providerProperties) {
    this(providerProperties, entry -> HttpClient.newBuilder()
        .connectTimeout(entry.getConnectTimeout()).build());
  }

  OpenAiFormatPassThroughClient(ProviderProperties providerProperties,
                                Function<ProviderProperties.ProviderEntry, HttpClient> http) {
    Map<String, ProviderProperties.ProviderEntry> byName = new LinkedHashMap<>();
    providerProperties.getProviders().forEach((name, entry) -> {
      byName.put(name.toLowerCase(Locale.ROOT), entry);
      clients.put(name.toLowerCase(Locale.ROOT), http.apply(entry));
    });
    this.providers = byName;
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
    HttpResponse<String> response = send(
        request(target, PassThroughJson.withModel(body, target.modelId()), "application/json"),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), target);
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw failure(target, status, response.body());
    }
    return new PassThroughResponse(status, response.body(), target.modelId(),
        PassThroughJson.usage(response.body()));
  }

  /**
   * Streams (v4 B.4). The instance's {@code read-timeout} bounds the wait for the
   * upstream's first byte, not the stream: a long answer is not cut, the SSE
   * timeout of the ingress bounds it.
   */
  @Override
  public UpstreamEventStream openStream(ModelDefinition target, String body) {
    HttpResponse<InputStream> response = send(
        request(target, PassThroughJson.forStream(body, target.modelId()), "text/event-stream"),
        HttpResponse.BodyHandlers.ofInputStream(), target);
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      String error;
      try (InputStream in = response.body()) {
        error = new String(in.readNBytes(LOGGED_ERROR_CHARS), StandardCharsets.UTF_8);
      } catch (IOException e) {
        error = e.toString();
      }
      throw failure(target, status, error);
    }
    return new SseUpstreamStream(response.body(), PassThroughJson.clientWantsUsage(body),
        target.modelId());
  }

  private HttpRequest request(ModelDefinition target, String body, String accept) {
    ProviderProperties.ProviderEntry entry = entry(target);
    HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(entry))
        .timeout(entry.getReadTimeout())
        .header("Content-Type", "application/json")
        .header("Accept", accept)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (entry.getApiKey() != null && !entry.getApiKey().isBlank()) {
      request.header("Authorization", "Bearer " + entry.getApiKey());
    }
    return request.build();
  }

  private static UpstreamFailureException failure(ModelDefinition target, int status,
                                                  String body) {
    LOG.warn("Pass-through upstream of model '{}' answered HTTP {}: {}", target.modelId(),
        status, truncate(body));
    return UpstreamFailureException.forStatus(status, target.modelId());
  }

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                   ModelDefinition target) {
    try {
      return clients.get(target.provider().toLowerCase(Locale.ROOT)).send(request, handler);
    } catch (HttpTimeoutException e) {
      LOG.warn("Pass-through upstream of model '{}' did not answer within {}",
          target.modelId(), request.timeout().map(Duration::toString).orElse("its timeout"));
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
