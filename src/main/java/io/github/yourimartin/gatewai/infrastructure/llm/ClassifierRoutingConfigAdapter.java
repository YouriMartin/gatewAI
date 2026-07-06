package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.Locale;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import org.springframework.stereotype.Component;

/**
 * Exposes the live {@link ClassifierProperties} as a {@link RoutingConfigPort}.
 * Updates mutate the singleton bean in place; the classifier reads it per call,
 * so changes take effect immediately (hot tuning, Phase 5.2).
 */
@Component
class ClassifierRoutingConfigAdapter implements RoutingConfigPort {

  private final ClassifierProperties properties;

  ClassifierRoutingConfigAdapter(ClassifierProperties properties) {
    this.properties = properties;
  }

  @Override
  public RoutingConfig get() {
    return new RoutingConfig(
        properties.getStrategy().name().toLowerCase(Locale.ROOT),
        properties.getEntryLengthThreshold(),
        properties.getPremiumLengthThreshold(),
        properties.getPremiumKeywords());
  }

  @Override
  public void update(RoutingConfig config) {
    properties.setStrategy(ClassifierStrategy.valueOf(
        config.strategy().toUpperCase(Locale.ROOT)));
    properties.setEntryLengthThreshold(config.entryLengthThreshold());
    properties.setPremiumLengthThreshold(config.premiumLengthThreshold());
    properties.setPremiumKeywords(config.premiumKeywords());
  }
}
