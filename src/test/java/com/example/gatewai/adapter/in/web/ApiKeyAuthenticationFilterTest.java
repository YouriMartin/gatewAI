package com.example.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.example.gatewai.domain.model.ApiClient;
import com.example.gatewai.domain.model.RequestContext;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

  @Mock
  private ApiClientRepository apiClientRepository;

  private ApiKeyAuthenticationFilter filter;

  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private MockFilterChain filterChain;

  @BeforeEach
  void setUp() {
    filter = new ApiKeyAuthenticationFilter(apiClientRepository);
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    filterChain = new MockFilterChain();
    SecurityContextHolder.clearContext();
  }

  @Test
  void validKeySetsAuthenticationAndScopedValue() throws Exception {
    String rawKey = "sk-test-key-12345";
    String keyHash = ApiKeyAuthenticationFilter.hashApiKey(rawKey);
    UUID clientId = UUID.randomUUID();
    ApiClient client = new ApiClient(
        clientId, "acme-corp", keyHash, true, Instant.now(), false
    );

    when(apiClientRepository.findByApiKeyHash(keyHash))
        .thenReturn(Optional.of(client));

    request.addHeader("Authorization", "Bearer " + rawKey);

    var scopedValueBound = new boolean[]{false};
    var capturedClientId = new String[]{null};
    FilterChain capturingChain = (ServletRequest req, ServletResponse res) -> {
      scopedValueBound[0] = RequestContext.CURRENT.isBound();
      if (scopedValueBound[0]) {
        capturedClientId[0] = RequestContext.CURRENT.get().clientId();
      }
    };

    filter.doFilterInternal(request, response, capturingChain);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertInstanceOf(ApiKeyAuthentication.class, auth);
    assertEquals(clientId.toString(), auth.getPrincipal());
    assertTrue(auth.isAuthenticated());

    assertTrue(scopedValueBound[0]);
    assertEquals(clientId.toString(), capturedClientId[0]);
  }

  @Test
  void missingAuthorizationHeaderDoesNotAuthenticate() throws Exception {
    filter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void invalidKeyDoesNotAuthenticate() throws Exception {
    String rawKey = "sk-invalid-key";
    String keyHash = ApiKeyAuthenticationFilter.hashApiKey(rawKey);

    when(apiClientRepository.findByApiKeyHash(keyHash))
        .thenReturn(Optional.empty());

    request.addHeader("Authorization", "Bearer " + rawKey);

    filter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void disabledClientDoesNotAuthenticate() throws Exception {
    String rawKey = "sk-disabled-key";
    String keyHash = ApiKeyAuthenticationFilter.hashApiKey(rawKey);
    ApiClient disabledClient = new ApiClient(
        UUID.randomUUID(), "disabled-corp", keyHash, false, Instant.now(), false
    );

    when(apiClientRepository.findByApiKeyHash(keyHash))
        .thenReturn(Optional.of(disabledClient));

    request.addHeader("Authorization", "Bearer " + rawKey);

    filter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void nonBearerAuthorizationHeaderDoesNotAuthenticate() throws Exception {
    request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

    filter.doFilterInternal(request, response, filterChain);

    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void hashApiKeyProducesConsistentSha256() {
    String hash = ApiKeyAuthenticationFilter.hashApiKey("test-key");

    assertEquals(64, hash.length());
    assertEquals(hash, ApiKeyAuthenticationFilter.hashApiKey("test-key"));
  }
}
