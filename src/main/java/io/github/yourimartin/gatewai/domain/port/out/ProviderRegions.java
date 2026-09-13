package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ProviderRegion;

/**
 * Reads the declared region of each egress provider instance (v3 lot C.2).
 * Outbound port: the shipped adapter reads {@code gatewai.providers.<name>.*},
 * and nothing in the domain needs to know that.
 *
 * <p>Kept separate from {@link ModelRegistry} on purpose — the registry answers
 * "which model, at what cost", this answers "whose datacenter, where". A model
 * id maps to a provider name, and that name maps here.
 */
public interface ProviderRegions {

  /**
   * What is known about where a provider instance runs. Present for every declared
   * instance, whether or not it declared a region — {@code isDeclared()} answers
   * that, and {@code operatorControlled()} is meaningful either way (v3 lot C.3).
   *
   * @param provider provider instance name, case-insensitive
   * @return the instance, or empty when no such instance is declared
   */
  Optional<ProviderRegion> findByProvider(String provider);

  /** Every declared provider instance, for startup reporting and diagnostics. */
  List<ProviderRegion> all();
}
