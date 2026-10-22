package io.github.yourimartin.gatewai.domain.model.routing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FailoverCauseTest {

  @Test
  void aConnectionFailureFailsOver() {
    assertThat(FailoverCause.of(new UpstreamFailureException(
        UpstreamFailureException.Kind.UNAVAILABLE, 0, "unreachable")))
        .contains(FailoverCause.CONNECTION_FAILED);
  }

  @Test
  void aTimeoutFailsOver() {
    assertThat(FailoverCause.of(new UpstreamFailureException(
        UpstreamFailureException.Kind.TIMEOUT, 0, "timed out")))
        .contains(FailoverCause.TIMEOUT);
  }

  @Test
  void aRateLimitFailsOver() {
    assertThat(FailoverCause.of(UpstreamFailureException.forStatus(429, "m")))
        .contains(FailoverCause.RATE_LIMITED);
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504, 529})
  void aServerErrorFailsOver(int status) {
    assertThat(FailoverCause.of(UpstreamFailureException.forStatus(status, "m")))
        .contains(FailoverCause.SERVER_ERROR);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 408, 413, 422})
  void anyOtherClientErrorReachesTheClient(int status) {
    assertThat(FailoverCause.of(UpstreamFailureException.forStatus(status, "m"))).isEmpty();
  }
}
