package com.example.gatewai.infrastructure.carbon;

import com.example.gatewai.domain.model.CarbonCalculator;
import com.example.gatewai.domain.model.GreenAccountant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the pure-domain carbon classes ({@link CarbonCalculator} and
 * {@link GreenAccountant}) as Spring beans so the application layer can inject
 * them for per-request green accounting (Phase 4.3).
 */
@Configuration
class CarbonConfiguration {

  @Bean
  CarbonCalculator carbonCalculator() {
    return new CarbonCalculator();
  }

  @Bean
  GreenAccountant greenAccountant(CarbonCalculator carbonCalculator) {
    return new GreenAccountant(carbonCalculator);
  }
}
