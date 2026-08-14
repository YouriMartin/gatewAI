package io.github.yourimartin.gatewai.adapter.in.web;

import java.io.IOException;

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
 */
class RateLimitFilter extends OncePerRequestFilter {

  private static final String CHAT_PATH = "/v1/chat/completions";
  private static final String EXPLAIN_PATH = "/v1/admin/decisions/explain";

  private final RateLimiter rateLimiter;
  private final RateLimitProperties properties;

  RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties properties) {
    this.rateLimiter = rateLimiter;
    this.properties = properties;
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

    RateLimitResult result = rateLimiter.tryAcquire(auth.getName());
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
