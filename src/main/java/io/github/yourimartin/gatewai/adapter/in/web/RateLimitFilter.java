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
 * Enforces per-client rate limits on the LLM endpoints (Phase 6.2). Runs after
 * authentication so the client id is known; only POSTs to
 * {@code /v1/chat/completions*} (sync + async submit) are limited — status polls
 * and admin/report calls are not. Returns {@code 429} with {@code Retry-After}.
 */
class RateLimitFilter extends OncePerRequestFilter {

  private static final String CHAT_PATH = "/v1/chat/completions";

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
    return "POST".equalsIgnoreCase(request.getMethod())
        && request.getRequestURI().startsWith(CHAT_PATH);
  }
}
