package io.github.yourimartin.gatewai.infrastructure.llm;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;

import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Reads what a provider client threw as an {@link UpstreamFailureException}
 * (ADR 0017, v4 B.5), so the chain classifies its failures like the
 * pass-through path does and the failover sees one vocabulary.
 *
 * <p>Each client reports differently: the OpenAI and Anthropic SDKs throw a
 * service exception carrying the status, or an I/O exception; Spring AI's Ollama
 * client throws {@code TransientAiException} (5xx) or
 * {@code NonTransientAiException} (4xx) whose message starts with the status, a
 * {@code ResourceAccessException} on a connection failure, and {@code WebClient}
 * exceptions when it streams. Any of them may arrive wrapped, so the whole cause
 * chain is read, and a status wins over a timeout, which wins over a bare I/O
 * failure.
 */
final class UpstreamFailures {

  /** Spring AI's {@code RetryUtils} formats errors as {@code "<status> - <body>"}. */
  private static final Pattern LEADING_STATUS = Pattern.compile("^(\\d{3}) - ");

  private UpstreamFailures() {
  }

  /**
   * The upstream failure {@code thrown} reports, or empty when it is not one
   * (a bug, a refused request, an unknown model): those reach the client as
   * they are, and never fail over.
   */
  static Optional<UpstreamFailureException> classify(Throwable thrown, String modelId) {
    List<Throwable> chain = causes(thrown);
    for (Throwable t : chain) {
      if (t instanceof UpstreamFailureException failure) {
        return Optional.of(failure);
      }
      Integer status = status(t);
      if (status != null) {
        return Optional.of(withCause(UpstreamFailureException.forStatus(status, modelId), t));
      }
    }
    for (Throwable t : chain) {
      if (isTimeout(t)) {
        return Optional.of(new UpstreamFailureException(UpstreamFailureException.Kind.TIMEOUT, 0,
            "Upstream of model '" + modelId + "' timed out", t));
      }
    }
    for (Throwable t : chain) {
      if (isConnectionFailure(t)) {
        return Optional.of(new UpstreamFailureException(UpstreamFailureException.Kind.UNAVAILABLE,
            0, "Upstream of model '" + modelId + "' is unreachable", t));
      }
    }
    return Optional.empty();
  }

  private static Integer status(Throwable t) {
    return switch (t) {
      case OpenAIServiceException e -> e.statusCode();
      case AnthropicServiceException e -> e.statusCode();
      case WebClientResponseException e -> e.getStatusCode().value();
      case RestClientResponseException e -> e.getStatusCode().value();
      case TransientAiException e -> leadingStatus(e.getMessage());
      case NonTransientAiException e -> leadingStatus(e.getMessage());
      default -> null;
    };
  }

  private static Integer leadingStatus(String message) {
    if (message == null) {
      return null;
    }
    Matcher matcher = LEADING_STATUS.matcher(message);
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  /**
   * OkHttp reports its call timeout as an {@link InterruptedIOException} whose
   * message is {@code "timeout"}; a plain interruption has another message.
   */
  private static boolean isTimeout(Throwable t) {
    return t instanceof SocketTimeoutException
        || t instanceof HttpTimeoutException
        || t instanceof TimeoutException
        || (t instanceof InterruptedIOException && "timeout".equals(t.getMessage()));
  }

  private static boolean isConnectionFailure(Throwable t) {
    return t instanceof IOException
        || t instanceof OpenAIIoException
        || t instanceof AnthropicIoException
        || t instanceof ResourceAccessException
        || t instanceof WebClientRequestException
        || t instanceof TransientAiException;
  }

  private static UpstreamFailureException withCause(UpstreamFailureException failure,
                                                    Throwable cause) {
    return new UpstreamFailureException(failure.kind(), failure.upstreamStatus(),
        failure.getMessage(), cause);
  }

  /** {@code thrown} and its causes, outermost first, each once. */
  private static List<Throwable> causes(Throwable thrown) {
    List<Throwable> chain = new ArrayList<>();
    Map<Throwable, Boolean> seen = new IdentityHashMap<>();
    for (Throwable t = thrown; t != null && seen.put(t, Boolean.TRUE) == null; t = t.getCause()) {
      chain.add(t);
    }
    return chain;
  }
}
