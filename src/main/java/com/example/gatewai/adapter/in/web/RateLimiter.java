package com.example.gatewai.adapter.in.web;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Per-client token-bucket rate limiter (Bucket4j, in-memory). One bucket per
 * client id; adequate for a single node (a distributed limiter would back it
 * with Redis/Hazelcast).
 */
class RateLimiter {

  private final RateLimitProperties properties;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  RateLimiter(RateLimitProperties properties) {
    this.properties = properties;
  }

  RateLimitResult tryAcquire(String clientId) {
    Bucket bucket = buckets.computeIfAbsent(clientId, id -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) {
      return RateLimitResult.granted();
    }
    long seconds = Math.max(1L,
        TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
    return RateLimitResult.limited(seconds);
  }

  private Bucket newBucket() {
    int perMinute = properties.getRequestsPerMinute();
    Bandwidth limit = Bandwidth.builder()
        .capacity(perMinute)
        .refillGreedy(perMinute, Duration.ofMinutes(1))
        .build();
    return Bucket.builder().addLimit(limit).build();
  }
}
