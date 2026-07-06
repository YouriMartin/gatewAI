package io.github.yourimartin.gatewai.adapter.in.web;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ApiClient;
import io.github.yourimartin.gatewai.domain.model.ApiKeyHasher;
import io.github.yourimartin.gatewai.domain.model.RequestContext;
import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

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
    SecurityContextHolder.getContext().setAuthentication(authentication);

    RequestContext ctx = new RequestContext(client.id().toString(), null);
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
