package com.example.gatewai.adapter.in.web;

import com.example.gatewai.domain.port.out.ApiClientRepository;

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

  @Bean
  RateLimiter rateLimiter(RateLimitProperties rateLimitProperties) {
    return new RateLimiter(rateLimitProperties);
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http,
                                          ApiClientRepository apiClientRepository,
                                          RateLimiter rateLimiter,
                                          RateLimitProperties rateLimitProperties)
      throws Exception {
    http
        .csrf(csrf -> csrf.disable())
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .addFilterBefore(
            new ApiKeyAuthenticationFilter(apiClientRepository),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(
            new RateLimitFilter(rateLimiter, rateLimitProperties),
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
