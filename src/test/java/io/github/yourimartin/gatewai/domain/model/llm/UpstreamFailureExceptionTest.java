package io.github.yourimartin.gatewai.domain.model.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UpstreamFailureExceptionTest {

  @ParameterizedTest
  @CsvSource({
      "400, REJECTED", "413, REJECTED", "422, REJECTED",
      "429, RATE_LIMITED",
      "401, MISCONFIGURED", "403, MISCONFIGURED", "404, MISCONFIGURED", "409, MISCONFIGURED",
      "500, UNAVAILABLE", "502, UNAVAILABLE", "529, UNAVAILABLE"
  })
  void anUpstreamStatusSaysWhoHasToAct(int status, UpstreamFailureException.Kind kind) {
    UpstreamFailureException e = UpstreamFailureException.forStatus(status, "gpt-5");

    assertThat(e.kind()).isEqualTo(kind);
    assertThat(e.upstreamStatus()).isEqualTo(status);
    assertThat(e.getMessage()).contains("gpt-5").contains(String.valueOf(status));
  }
}
