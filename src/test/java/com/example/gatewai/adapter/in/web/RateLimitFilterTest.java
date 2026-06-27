package com.example.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

  private RateLimitProperties properties;
  private RateLimitFilter filter;

  @BeforeEach
  void setUp() {
    properties = new RateLimitProperties();
    filter = new RateLimitFilter(new RateLimiter(properties), properties);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private static void authenticate(String clientId) {
    SecurityContextHolder.getContext().setAuthentication(
        new ApiKeyAuthentication(clientId, "name"));
  }

  private static MockHttpServletRequest post() {
    return new MockHttpServletRequest("POST", "/v1/chat/completions");
  }

  @Test
  void allowsRequestUnderLimit() throws Exception {
    properties.setRequestsPerMinute(5);
    authenticate("client");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(post(), response, chain);

    assertEquals(200, response.getStatus());
    assertNotNull(chain.getRequest());
  }

  @Test
  void blocksWith429WhenLimitExceeded() throws Exception {
    properties.setRequestsPerMinute(1);
    authenticate("client");
    filter.doFilter(post(), new MockHttpServletResponse(), new MockFilterChain());

    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(post(), response, chain);

    assertEquals(429, response.getStatus());
    assertNotNull(response.getHeader("Retry-After"));
    assertNull(chain.getRequest());
  }

  @Test
  void doesNotLimitNonChatPaths() throws Exception {
    properties.setRequestsPerMinute(1);
    authenticate("client");
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/v1/reports/green");
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest());
  }

  @Test
  void doesNotLimitUnauthenticatedRequests() throws Exception {
    properties.setRequestsPerMinute(1);
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(post(), new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest());
  }

  @Test
  void passesThroughWhenDisabled() throws Exception {
    properties.setEnabled(false);
    properties.setRequestsPerMinute(1);
    authenticate("client");
    filter.doFilter(post(), new MockHttpServletResponse(), new MockFilterChain());

    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(post(), new MockHttpServletResponse(), chain);

    assertNotNull(chain.getRequest());
  }
}
