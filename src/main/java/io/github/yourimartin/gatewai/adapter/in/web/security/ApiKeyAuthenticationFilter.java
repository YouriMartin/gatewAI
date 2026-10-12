package io.github.yourimartin.gatewai.adapter.in.web.security;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.client.ApiClient;
import io.github.yourimartin.gatewai.domain.model.client.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.context.RequestContext;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  /**
   * Where the authenticated context is saved for the rest of the request. A
   * streamed response ends with an async dispatch — and a failure with an error
   * dispatch — that runs the security chain again without this filter
   * ({@link OncePerRequestFilter} skips them); both reload the context from the
   * request attributes. Without it that dispatch was denied after the response
   * was committed, and the stream's chunked body was cut short.
   */
  private final SecurityContextRepository contextRepository =
      new RequestAttributeSecurityContextRepository();

  private final ApiClientRepository apiClientRepository;

  ApiKeyAuthenticationFilter(ApiClientRepository apiClientRepository) {
    this.apiClientRepository = apiClientRepository;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request,
                                  HttpServletResponse response,
                                  FilterChain filterChain)
      throws ServletException, IOException {

    String authHeader = request.getHeader("Authorization");

    if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
      filterChain.doFilter(request, response);
      return;
    }

    String rawKey = authHeader.substring(BEARER_PREFIX.length());
    String keyHash = hashApiKey(rawKey);

    ApiClient client = apiClientRepository.findByApiKeyHash(keyHash)
        .filter(ApiClient::enabled)
        .orElse(null);

    if (client == null) {
      filterChain.doFilter(request, response);
      return;
    }

    ApiKeyAuthentication authentication = new ApiKeyAuthentication(
        client.id().toString(), client.name(), authorities(client));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, request, response);

    RequestContext ctx = new RequestContext(
        client.id().toString(), CorrelationIdFilter.from(request));
    ScopedValue.where(RequestContext.CURRENT, ctx).run(() -> {
      try {
        filterChain.doFilter(request, response);
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      } catch (ServletException e) {
        throw new IllegalStateException(e);
      }
    });
    return;
  }

  private static Collection<GrantedAuthority> authorities(ApiClient client) {
    return client.admin()
        ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        : List.of();
  }

  static String hashApiKey(String rawKey) {
    return ApiKeyHasher.hash(rawKey);
  }
}
