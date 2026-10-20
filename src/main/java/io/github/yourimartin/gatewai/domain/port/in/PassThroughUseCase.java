package io.github.yourimartin.gatewai.domain.port.in;

import io.github.yourimartin.gatewai.domain.model.llm.PassThroughRequest;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.UnknownModelException;
import io.github.yourimartin.gatewai.domain.model.llm.UnsupportedFeatureException;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

/**
 * Serves a request the advisor chain cannot honour (ADR 0016, v4 B.3): forwards it
 * as-is to one registered model, traces why and accounts what it cost.
 */
public interface PassThroughUseCase {

  /**
   * Forwards {@code request} and returns the upstream answer.
   *
   * @throws UnsupportedFeatureException when the target's endpoint would ignore a
   *                                     feature of the request
   * @throws UpstreamFailureException    when the upstream answers with an error,
   *                                     or does not answer
   * @throws UnknownModelException       when no target can be resolved
   */
  PassThroughResponse forward(PassThroughRequest request);
}
