package io.github.yourimartin.gatewai.adapter.in.web;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Per-client rate limiting, with two interchangeable stores (v3 lot B.3):
 * {@link InMemoryRateLimiter} for a single node and {@link PostgresRateLimiter}
 * for a cluster.
 *
 * <p>The two shared statics below are the point of having an interface here
 * rather than two unrelated classes. The limit and the {@code Retry-After} it
 * reports are defined <b>once</b>, so "60 requests per minute" cannot come to
 * mean two slightly different things depending on which store a deployment
 * happens to run.
 */
interface RateLimiter {

  RateLimitResult tryAcquire(String clientId);

  /** The one definition of the limit both stores enforce. */
  static Bandwidth bandwidth(int requestsPerMinute) {
    return Bandwidth.builder()
        .capacity(requestsPerMinute)
        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
        .build();
  }

  /**
   * Translates a Bucket4j probe into the filter's answer, rounding the wait
   * <b>up</b> to at least one second: {@code Retry-After: 0} invites an immediate
   * retry that is guaranteed to fail again.
   */
  static RateLimitResult resultOf(ConsumptionProbe probe) {
    if (probe.isConsumed()) {
      return RateLimitResult.granted();
    }
    long seconds = Math.max(1L,
        TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
    return RateLimitResult.limited(seconds);
  }
}
