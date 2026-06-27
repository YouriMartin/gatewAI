package com.example.gatewai.infrastructure.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring scheduling only when carbon-aware dispatch is enabled, so the
 * {@link CarbonAwareDispatchWorker} runs without forcing scheduling otherwise.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "gatewai.dispatch", name = "enabled",
    havingValue = "true")
class DispatchSchedulingConfig {
}
