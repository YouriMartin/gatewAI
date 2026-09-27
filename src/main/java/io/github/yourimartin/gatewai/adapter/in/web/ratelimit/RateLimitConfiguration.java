package io.github.yourimartin.gatewai.adapter.in.web.ratelimit;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Rate-limit wiring: the properties and the store behind {@link RateLimiter}.
 * Imported by the security configuration, which places the {@link RateLimitFilter}
 * in the filter chain, so the store implementations stay package-private here.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

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
}
