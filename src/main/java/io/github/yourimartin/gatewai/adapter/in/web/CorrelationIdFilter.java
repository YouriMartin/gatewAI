package io.github.yourimartin.gatewai.adapter.in.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a correlation id to every inbound request (v2 batch 0.3).
 *
 * <p>Runs before {@link ApiKeyAuthenticationFilter}, which reads the id back
 * from the request attribute and binds it into {@code RequestContext}, so the
 * whole advisor chain — cache, router, green accounting — shares one id. It is
 * the join key between the carbon/cost record and the routing and cache
 * decisions persisted in batch 2.
 *
 * <p>An inbound {@code X-Request-Id} is honoured so the gateway joins a caller's
 * existing trace instead of starting a new one; anything unusable is replaced by
 * a generated id rather than rejected — a malformed header must not fail a chat
 * completion. The id is always echoed back, including on unauthenticated
 * requests, so a client can quote it when reporting a problem.
 */
class CorrelationIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Request-Id";
  static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
  static final int MAX_LENGTH = 64;

  @Override
  protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain)
      throws ServletException, IOException {

    String correlationId = sanitize(request.getHeader(HEADER));
    if (correlationId == null) {
      correlationId = UUID.randomUUID().toString();
    }

    request.setAttribute(ATTRIBUTE, correlationId);
    response.setHeader(HEADER, correlationId);

    filterChain.doFilter(request, response);
  }

  /** The current request's id, or {@code null} outside the filter's reach. */
  static String from(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    return value instanceof String id ? id : null;
  }

  /**
   * Keeps only ids that are safe to echo in a header and to store in a 64-char
   * column: printable ASCII, no separators, no CR/LF (response splitting).
   */
  private static String sanitize(String candidate) {
    if (candidate == null || candidate.isBlank()
        || candidate.length() > MAX_LENGTH) {
      return null;
    }
    for (int i = 0; i < candidate.length(); i++) {
      char c = candidate.charAt(i);
      boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
      if (!allowed) {
        return null;
      }
    }
    return candidate;
  }
}
