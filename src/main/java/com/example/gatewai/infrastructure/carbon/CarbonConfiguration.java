package com.example.gatewai.infrastructure.carbon;

import com.example.gatewai.domain.model.CarbonCalculator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the pure-domain {@link CarbonCalculator} as a Spring bean so the
 * green-accounting advisor (Phase 4.3) can inject it.
 */
@Configuration
class CarbonConfiguration {

  @Bean
  CarbonCalculator carbonCalculator() {
    return new CarbonCalculator();
  }
}
