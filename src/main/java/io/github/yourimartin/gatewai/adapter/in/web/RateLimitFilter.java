package io.github.yourimartin.gatewai.adapter.in.web;

import java.io.IOException;
import java.util.Locale;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces per-client rate limits on the endpoints that cost model calls
 * (Phase 6.2, extended in v2 batch 9). Runs after authentication so the client
 * id is known. Returns {@code 429} with {@code Retry-After}.
 *
 * <p>Limited: POSTs to {@code /v1/chat/completions*} (sync + async submit) and
 * to {@code /v1/admin/decisions/explain}, which embeds a prompt once per segment
 * plus one against the same local model that serves traffic. Not limited: status
 * polls, reports, and the {@code GET} decision endpoints, which only read rows.
 *
 * <p>The check is timed as {@code gatewai.ratelimit.check}, tagged with the store
 * (v3 lot B.3). With the Postgres store this is a row lock on the request path,
 * and "how much did that cost" should be a reading rather than an argument — it is
 * also what tells you whether a token-batching optimisation is worth its
 * complexity.
 */
class RateLimitFilter extends OncePerRequestFilter {

  private static final String CHAT_PATH = "/v1/chat/completions";
  private static final String EXPLAIN_PATH = "/v1/admin/decisions/explain";

  private final RateLimiter rateLimiter;
  private final RateLimitProperties properties;
  private final Timer checkLatency;

  RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties,
                  MeterRegistry registry) {
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.checkLatency = Timer.builder("gatewai.ratelimit.check")
        .description("Time spent deciding whether a request is within its limit")
        .tag("store", properties.getStore().name().toLowerCase(Locale.ROOT))
        .register(registry);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain)
      throws ServletException, IOException {

    if (!properties.isEnabled() || !appliesTo(request)) {
      filterChain.doFilter(request, response);
      return;
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated()) {
      filterChain.doFilter(request, response);
      return;
    }

    RateLimitResult result =
        checkLatency.record(() -> rateLimiter.tryAcquire(auth.getName()));
    if (result.allowed()) {
      filterChain.doFilter(request, response);
      return;
    }

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER,
        String.valueOf(result.retryAfterSeconds()));
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write("{\"error\":\"rate limit exceeded\"}");
  }

  private static boolean appliesTo(HttpServletRequest request) {
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      return false;
    }
    String path = request.getRequestURI();
    return path.startsWith(CHAT_PATH) || path.equals(EXPLAIN_PATH);
  }
}
