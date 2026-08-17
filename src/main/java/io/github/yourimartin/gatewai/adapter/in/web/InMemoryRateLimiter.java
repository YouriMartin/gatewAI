package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bucket4j.Bucket;

/**
 * Token buckets in the heap, one per client id (Phase 6.2). Correct and fast on a
 * single node, and wrong the moment there are two: each process would grant the
 * full quota, so N replicas allow N × the limit. Use
 * {@link PostgresRateLimiter} there.
 */
class InMemoryRateLimiter implements RateLimiter {

  private final RateLimitProperties properties;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  InMemoryRateLimiter(RateLimitProperties properties) {
    this.properties = properties;
  }

  @Override
  public RateLimitResult tryAcquire(String clientId) {
    Bucket bucket = buckets.computeIfAbsent(clientId, id -> newBucket());
    return RateLimiter.resultOf(bucket.tryConsumeAndReturnRemaining(1));
  }

  private Bucket newBucket() {
    return Bucket.builder()
        .addLimit(RateLimiter.bandwidth(properties.getRequestsPerMinute()))
        .build();
  }
}
