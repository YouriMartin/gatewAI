package io.github.yourimartin.gatewai.adapter.in.web.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ApiClientRepository apiClientRepository;

  /**
   * A pass-through stream is written by its own thread once the controller returns,
   * so nothing may write headers after that: Spring Security's are already on the
   * response when the handler runs. Written on the way back out, they raced the
   * relay thread (a ConcurrentModificationException, or a stream cut after its
   * first chunk, in PassThroughAcceptanceTest on CI).
   */
  @Test
  void securityHeadersAreWrittenBeforeTheHandlerRuns() throws Exception {
    mockMvc.perform(get("/v1/probe")
            .with(authentication(new ApiKeyAuthentication("client-id", "client"))))
        .andExpect(status().isOk())
        .andExpect(content().string("nosniff"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @RestController
  static class ProbeController {

    /** What the handler sees of the header, not what the client finally gets. */
    @GetMapping("/v1/probe")
    String probe(HttpServletResponse response) {
      return String.valueOf(response.getHeader("X-Content-Type-Options"));
    }
  }
}
