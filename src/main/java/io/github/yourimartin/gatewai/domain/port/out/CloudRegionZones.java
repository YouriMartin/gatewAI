package io.github.yourimartin.gatewai.domain.port.out;

import java.util.Optional;

/**
 * Translates a cloud region id ({@code us-east-1}) into the electricity grid
 * zone that serves it ({@code US-MIDA-PJM}), so a provider's region can be
 * priced at the right grid intensity (v3 lot C.2).
 *
 * <p>Outbound port: the shipped adapter carries a small built-in table, lets
 * configuration override any entry, and <b>never throws</b> — an unrecognised
 * region returns empty so the caller falls back to the gateway's default zone.
 * Zero-config boot must keep working.
 */
public interface CloudRegionZones {

  /**
   * The grid zone serving {@code region}.
   *
   * @param region a cloud region id, or a grid zone id already
   * @return the zone id, or empty when the region is not recognised
   */
  Optional<String> zoneFor(String region);
}
