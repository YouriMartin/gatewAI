package io.github.yourimartin.gatewai.domain.port.out;

import java.util.Set;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughFeature;
import io.github.yourimartin.gatewai.domain.model.llm.PassThroughResponse;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;

/**
 * Sends a request, as the client wrote it, to the OpenAI-format endpoint of a
 * model's provider instance (ADR 0016, v4 B.3). Implementations own every JSON
 * operation: rewriting {@code model} in the body, reading {@code usage} back.
 */
public interface PassThroughClient {

  /**
   * The features {@code target}'s endpoint is documented to ignore. A request
   * using one is refused before it is sent, never forwarded to be silently
   * degraded. Empty when nothing is declared for the endpoint.
   */
  Set<PassThroughFeature> ignoredFeatures(ModelDefinition target);

  /** What {@code target}'s endpoint is, in words a client can act on. */
  String describeEndpoint(ModelDefinition target);

  /**
   * Forwards {@code body} with its {@code model} rewritten to
   * {@code target.modelId()}.
   *
   * @return the 2xx answer, with the usage the upstream reported
   * @throws UpstreamFailureException on any non-2xx answer, a transport failure
   *                                  or a timeout
   */
  PassThroughResponse forward(ModelDefinition target, String body);

  /**
   * Opens a streamed call (v4 B.4): {@code body} with {@code model} rewritten and
   * {@code stream_options.include_usage} turned on, so the request can be
   * accounted. Returns once the upstream has answered 2xx.
   *
   * @throws UpstreamFailureException on a non-2xx answer, a transport failure or a
   *                                  timeout before the first byte
   */
  UpstreamEventStream openStream(ModelDefinition target, String body);
}
