package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private CorrelationIdFilter filter;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;
  private MockFilterChain chain;

  @BeforeEach
  void setUp() {
    filter = new CorrelationIdFilter();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
    chain = new MockFilterChain();
  }

  @Test
  void generatesAnIdWhenTheHeaderIsAbsent() throws Exception {
    filter.doFilterInternal(request, response, chain);

    String id = CorrelationIdFilter.from(request);
    assertNotNull(id);
    assertDoesNotThrow(() -> UUID.fromString(id));
    assertEquals(id, response.getHeader(CorrelationIdFilter.HEADER));
  }

  @Test
  void honoursAnInboundId() throws Exception {
    request.addHeader(CorrelationIdFilter.HEADER, "req-abc-123");

    filter.doFilterInternal(request, response, chain);

    assertEquals("req-abc-123", CorrelationIdFilter.from(request));
    assertEquals("req-abc-123", response.getHeader(CorrelationIdFilter.HEADER));
  }

  @Test
  void replacesAnIdCarryingHeaderInjectionCharacters() throws Exception {
    request.addHeader(CorrelationIdFilter.HEADER, "abc\r\nX-Evil: 1");

    filter.doFilterInternal(request, response, chain);

    String id = CorrelationIdFilter.from(request);
    assertNotEquals("abc\r\nX-Evil: 1", id);
    assertDoesNotThrow(() -> UUID.fromString(id));
  }

  @Test
  void replacesAnIdLongerThanTheColumn() throws Exception {
    request.addHeader(CorrelationIdFilter.HEADER,
        "x".repeat(CorrelationIdFilter.MAX_LENGTH + 1));

    filter.doFilterInternal(request, response, chain);

    assertTrue(CorrelationIdFilter.from(request).length()
        <= CorrelationIdFilter.MAX_LENGTH);
  }

  @Test
  void replacesABlankId() throws Exception {
    request.addHeader(CorrelationIdFilter.HEADER, "   ");

    filter.doFilterInternal(request, response, chain);

    assertDoesNotThrow(
        () -> UUID.fromString(CorrelationIdFilter.from(request)));
  }

  @Test
  void readsNullFromARequestTheFilterNeverTouched() {
    assertNull(CorrelationIdFilter.from(new MockHttpServletRequest()));
  }
}
