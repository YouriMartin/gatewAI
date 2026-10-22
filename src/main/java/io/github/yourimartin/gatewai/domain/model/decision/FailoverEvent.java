package io.github.yourimartin.gatewai.domain.model.decision;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.UpstreamFailureException;
import io.github.yourimartin.gatewai.domain.model.routing.FailoverCause;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

/**
 * A request left a registry entry for the next one of its tier (ADR 0017, v4
 * B.5) — or found none left. One per failed attempt of a request that was
 * allowed to fail over; a request that was not writes none.
 *
 * <p>The routing decision says where the request was sent first, and the
 * request log which model answered: these rows are what happened in between.
 *
 * @param id             surrogate id
 * @param correlationId  the ingress-assigned id shared with the request's other rows
 * @param createdAt      when the attempt failed
 * @param path           which path the request took
 * @param tier           the tier of the whole chain
 * @param fromModelId    the registry model id that failed
 * @param toModelId      the next one tried, null when nothing was left: the
 *                       client then received this failure
 * @param cause          why the attempt counts as transient
 * @param upstreamStatus the provider's HTTP status, null when it never answered
 */
public record FailoverEvent(
    UUID id,
    String correlationId,
    Instant createdAt,
    Path path,
    ModelTier tier,
    String fromModelId,
    String toModelId,
    FailoverCause cause,
    Integer upstreamStatus
) {

  /**
   * The attempt on {@code from} failed with {@code failure}, for {@code cause}.
   *
   * @param to the next model tried, null when none was left
   */
  public static FailoverEvent of(String correlationId, Path path, ModelDefinition from,
                                 ModelDefinition to, FailoverCause cause,
                                 UpstreamFailureException failure) {
    return new FailoverEvent(UUID.randomUUID(), correlationId, Instant.now(), path,
        from.tier(), from.modelId(), to == null ? null : to.modelId(), cause,
        failure.upstreamStatus() == 0 ? null : failure.upstreamStatus());
  }

  /** The two ways a request reaches a provider (ADR 0016). */
  public enum Path {
    /** The advisor chain. */
    CHAIN,
    /** The pass-through path. */
    PASS_THROUGH
  }
}
