package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.yourimartin.gatewai.domain.model.ProviderRegion;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads provider regions from {@code gatewai.providers.<name>.*} (v3 lot C.2).
 *
 * <p>Only instances that actually declare a region are exposed: an undeclared one
 * returns empty so the caller falls back to the gateway's default zone rather than
 * to a fabricated location. Provider names are matched case-insensitively, like
 * everywhere else on the egress path.
 *
 * <p>Built once at startup, so a PUE below 1 fails the context rather than
 * surfacing on the first request.
 */
@Component
class PropertiesProviderRegions implements ProviderRegions {

  private static final Logger LOG = LoggerFactory.getLogger(PropertiesProviderRegions.class);

  private static final double MIN_PUE = 1.0;

  private final Map<String, ProviderRegion> regions;

  PropertiesProviderRegions(ProviderProperties properties) {
    Map<String, ProviderRegion> declared = new LinkedHashMap<>();
    properties.getProviders().forEach((name, entry) -> {
      if (entry == null || entry.getRegion() == null || entry.getRegion().isBlank()) {
        return;
      }
      validatePue(name, entry.getPue());
      declared.put(name.toLowerCase(Locale.ROOT), new ProviderRegion(
          name, entry.getRegion().trim(), entry.getRegionProvenance(), entry.getPue()));
    });
    this.regions = Map.copyOf(declared);
    if (regions.isEmpty()) {
      LOG.info("No egress provider declares a region — every request is booked at the"
          + " gateway's default grid zone.");
    } else {
      LOG.info("Provider regions declared: {}", describe());
    }
  }

  /** {@code name=region (provenance, PUE)} per instance, for the startup line. */
  private String describe() {
    return regions.values().stream()
        .map(region -> region.provider() + "=" + region.region()
            + " (" + region.provenance().label()
            + (region.pue() == null ? ", no PUE" : ", PUE " + region.pue()) + ")")
        .collect(Collectors.joining(", "));
  }

  @Override
  public Optional<ProviderRegion> findByProvider(String provider) {
    return provider == null
        ? Optional.empty()
        : Optional.ofNullable(regions.get(provider.toLowerCase(Locale.ROOT)));
  }

  @Override
  public List<ProviderRegion> all() {
    return List.copyOf(regions.values());
  }

  private static void validatePue(String name, Double pue) {
    if (pue != null && pue < MIN_PUE) {
      throw new IllegalStateException("Provider '" + name + "' declares pue=" + pue
          + ", which is physically impossible (a datacenter cannot deliver more energy"
          + " to its servers than it draws). Fix gatewai.providers." + name + ".pue.");
    }
  }
}
