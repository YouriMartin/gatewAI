package com.example.gatewai.adapter.in.web;

/** Outcome of a rate-limit check. */
record RateLimitResult(boolean allowed, long retryAfterSeconds) {

  static RateLimitResult granted() {
    return new RateLimitResult(true, 0L);
  }

  static RateLimitResult limited(long retryAfterSeconds) {
    return new RateLimitResult(false, retryAfterSeconds);
  }
}
