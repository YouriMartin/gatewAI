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
   * The region declared for a provider instance.
   *
   * @param provider provider instance name, case-insensitive
   * @return the region, or empty when the instance is unknown or declared none
   */
  Optional<ProviderRegion> findByProvider(String provider);

  /** Every declared provider region, for startup reporting and diagnostics. */
  List<ProviderRegion> all();
}
