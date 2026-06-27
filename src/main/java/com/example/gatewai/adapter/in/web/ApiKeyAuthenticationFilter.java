package com.example.gatewai.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.gatewai.domain.model.ApiClient;
import com.example.gatewai.domain.model.RequestContext;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

    ApiKeyAuthentication authentication =
        new ApiKeyAuthentication(client.id().toString(), client.name());
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

  static String hashApiKey(String rawKey) {
    MessageDigest digest = sha256();
    digest.update(rawKey.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 is guaranteed by the JDK", e);
    }
  }
}
