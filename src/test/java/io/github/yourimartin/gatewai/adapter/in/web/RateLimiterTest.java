package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

  private static RateLimiter limiter(int perMinute) {
    RateLimitProperties properties = new RateLimitProperties();
    properties.setRequestsPerMinute(perMinute);
    return new RateLimiter(properties);
  }

  @Test
  void allowsUpToLimitThenBlocksWithRetryAfter() {
    RateLimiter limiter = limiter(3);

    assertTrue(limiter.tryAcquire("client").allowed());
    assertTrue(limiter.tryAcquire("client").allowed());
    assertTrue(limiter.tryAcquire("client").allowed());

    RateLimitResult blocked = limiter.tryAcquire("client");
    assertFalse(blocked.allowed());
    assertTrue(blocked.retryAfterSeconds() >= 1);
  }

  @Test
  void bucketsAreIsolatedPerClient() {
    RateLimiter limiter = limiter(1);

    assertTrue(limiter.tryAcquire("a").allowed());
    assertFalse(limiter.tryAcquire("a").allowed());
    assertTrue(limiter.tryAcquire("b").allowed());
  }
}
