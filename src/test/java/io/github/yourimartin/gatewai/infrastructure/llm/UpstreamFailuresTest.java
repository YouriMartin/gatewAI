package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;

import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException.Kind;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class UpstreamFailuresTest {

  @Test
  void readsTheStatusOfAnOpenAiServiceException() {
    OpenAIServiceException e = mock(OpenAIServiceException.class);
    when(e.statusCode()).thenReturn(429);

    assertThat(classify(e)).satisfies(f -> {
      assertThat(f.kind()).isEqualTo(Kind.RATE_LIMITED);
      assertThat(f.upstreamStatus()).isEqualTo(429);
    });
  }

  @Test
  void readsTheStatusOfAnAnthropicServiceExceptionIncludingOverloaded() {
    AnthropicServiceException e = mock(AnthropicServiceException.class);
    when(e.statusCode()).thenReturn(529);

    assertThat(classify(e)).satisfies(f -> {
      assertThat(f.kind()).isEqualTo(Kind.UNAVAILABLE);
      assertThat(f.upstreamStatus()).isEqualTo(529);
    });
  }

  @Test
  void readsTheStatusSpringAiPutsAtTheStartOfItsMessage() {
    assertThat(classify(new TransientAiException("503 - overloaded")).upstreamStatus())
        .isEqualTo(503);
    assertThat(classify(new NonTransientAiException("429 - slow down")).kind())
        .isEqualTo(Kind.RATE_LIMITED);
    assertThat(classify(new NonTransientAiException("401 - bad key")).kind())
        .isEqualTo(Kind.MISCONFIGURED);
  }

  @Test
  void readsTheStatusOfAStreamingWebClientFailure() {
    WebClientResponseException e = WebClientResponseException.create(502, "Bad Gateway",
        HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

    assertThat(classify(e).upstreamStatus()).isEqualTo(502);
  }

  @Test
  void findsTheStatusUnderWrappers() {
    OpenAIServiceException e = mock(OpenAIServiceException.class);
    when(e.statusCode()).thenReturn(500);

    assertThat(classify(new CompletionException(new RuntimeException(e))).upstreamStatus())
        .isEqualTo(500);
  }

  @Test
  void aTimeoutAnywhereInTheChainIsATimeout() {
    assertThat(classify(new ResourceAccessException("I/O error",
        new SocketTimeoutException("Read timed out"))).kind()).isEqualTo(Kind.TIMEOUT);
    assertThat(classify(new HttpTimeoutException("request timed out")).kind())
        .isEqualTo(Kind.TIMEOUT);
    // How OkHttp reports a call timeout under the SDKs.
    assertThat(classify(new OpenAIIoException("Request failed",
        new InterruptedIOException("timeout"))).kind()).isEqualTo(Kind.TIMEOUT);
  }

  @Test
  void aConnectionFailureIsUnavailableWithNoStatus() {
    UpstreamFailureException failure = classify(new ResourceAccessException("I/O error",
        new ConnectException("Connection refused")));

    assertThat(failure.kind()).isEqualTo(Kind.UNAVAILABLE);
    assertThat(failure.upstreamStatus()).isZero();
  }

  @Test
  void anUpstreamFailureIsKeptAsItIs() {
    UpstreamFailureException original = UpstreamFailureException.forStatus(503, "m");

    assertThat(classify(original)).isSameAs(original);
  }

  @Test
  void anythingElseIsNotAnUpstreamFailure() {
    assertThat(UpstreamFailures.classify(new IllegalArgumentException("bug"), "m")).isEmpty();
    assertThat(UpstreamFailures.classify(new NonTransientAiException("no status"), "m"))
        .isEmpty();
  }

  private static UpstreamFailureException classify(Throwable thrown) {
    return UpstreamFailures.classify(thrown, "m").orElseThrow();
  }
}
