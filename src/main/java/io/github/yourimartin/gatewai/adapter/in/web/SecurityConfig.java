package io.github.yourimartin.gatewai.adapter.in.web;

import javax.sql.DataSource;

import io.github.yourimartin.gatewai.domain.port.out.ApiClientRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class SecurityConfig {

  /**
   * The rate-limit store, chosen by configuration (v3 lot B.3). The default stays
   * in-memory: it is correct and free on one node, which is how most deployments
   * of a self-hosted gateway run, and the shared store is one property away for
   * the ones that scale out.
   */
  @Bean
  RateLimiter rateLimiter(RateLimitProperties rateLimitProperties,
                          ObjectProvider<DataSource> dataSource) {
    return switch (rateLimitProperties.getStore()) {
      case MEMORY -> new InMemoryRateLimiter(rateLimitProperties);
      // Resolved only on this branch: an ObjectProvider keeps the security
      // configuration loadable in a @WebMvcTest slice, which has no DataSource
      // and does not need one to test a filter chain.
      case POSTGRES ->
          new PostgresRateLimiter(rateLimitProperties, dataSource.getObject());
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http,
                                          ApiClientRepository apiClientRepository,
                                          RateLimiter rateLimiter,
                                          RateLimitProperties rateLimitProperties,
                                          ObjectProvider<MeterRegistry> registry)
      throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(
            new ApiKeyAuthenticationFilter(apiClientRepository),
            UsernamePasswordAuthenticationFilter.class)
        // Must precede the auth filter: that one reads the id back when it
        // binds the RequestContext for the advisor chain (v2 batch 0.3).
        .addFilterBefore(new CorrelationIdFilter(), ApiKeyAuthenticationFilter.class)
        .addFilterAfter(
            new RateLimitFilter(rateLimiter, rateLimitProperties,
                // Actuator always provides one; a @WebMvcTest slice does not, and
                // a filter-chain test should not have to care about metrics.
                registry.getIfAvailable(SimpleMeterRegistry::new)),
            ApiKeyAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info",
                "/actuator/prometheus").permitAll()
            // Dashboard SPA shell (static assets). Data under /v1/** stays secured.
            .requestMatchers("/", "/index.html", "/assets/**",
                "/favicon.ico", "/favicon.svg", "/vite.svg").permitAll()
            .requestMatchers("/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/v1/**").authenticated()
            // MCP server endpoint (Phase 6.4): same Bearer API key as /v1/**.
            .requestMatchers("/mcp/**", "/mcp").authenticated()
            .anyRequest().authenticated());
    return http.build();
  }
}
