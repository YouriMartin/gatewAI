package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.yourimartin.gatewai.domain.model.ProviderRegion;
import io.github.yourimartin.gatewai.domain.model.RegionProvenance;
import io.github.yourimartin.gatewai.domain.port.out.ProviderRegions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads provider regions from {@code gatewai.providers.<name>.*} (v3 lot C.2).
 *
 * <p>Every declared instance is exposed, with or without a region:
 * {@link ProviderRegion#isDeclared()} says which, and an undeclared region leaves
 * the caller to fall back to the gateway's default zone rather than to a fabricated
 * location. Provider names are matched case-insensitively, like everywhere else on
 * the egress path.
 *
 * <p><b>Operator-controlled</b> (v3 lot C.3) is derived here, because the provider
 * type is an infrastructure concept: an instance is controlled when it is
 * self-hostable ({@code ollama}, {@code openai-compatible}) <em>and</em> has not
 * declared an {@code assumed} region. The second half matters for a hosted
 * OpenAI-compatible endpoint — OpenRouter, say: declaring its region as assumed is
 * exactly the statement "I do not place this workload", and a dispatch-chosen zone
 * must not override it. Declaring nothing keeps the default local setup controlled,
 * which is what carbon-aware dispatch has always assumed.
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
      if (entry == null) {
        return;
      }
      validatePue(name, entry.getPue());
      String region = entry.getRegion() == null || entry.getRegion().isBlank()
          ? null : entry.getRegion().trim();
      declared.put(name.toLowerCase(Locale.ROOT), new ProviderRegion(
          name, region, entry.getRegionProvenance(), entry.getPue(),
          operatorControlled(entry, region)));
    });
    this.regions = Map.copyOf(declared);
    if (regions.values().stream().noneMatch(ProviderRegion::isDeclared)) {
      LOG.info("No egress provider declares a region — every request is booked at the"
          + " gateway's default grid zone.");
    } else {
      LOG.info("Provider regions declared: {}", describe());
    }
  }

  /** {@code name=region (provenance, PUE)} per declared region, for the startup line. */
  private String describe() {
    return regions.values().stream()
        .filter(ProviderRegion::isDeclared)
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

  /**
   * Self-hostable type, minus an explicit {@code assumed} region — see the class
   * javadoc. A type the gateway does not know (unset) is not controlled.
   */
  private static boolean operatorControlled(ProviderProperties.ProviderEntry entry,
                                            String region) {
    boolean selfHostable = entry.getType() == ProviderProperties.ProviderType.OLLAMA
        || entry.getType() == ProviderProperties.ProviderType.OPENAI_COMPATIBLE;
    boolean declaredAsAssumed = region != null
        && entry.getRegionProvenance() != RegionProvenance.KNOWN;
    return selfHostable && !declaredAsAssumed;
  }

  private static void validatePue(String name, Double pue) {
    if (pue != null && pue < MIN_PUE) {
      throw new IllegalStateException("Provider '" + name + "' declares pue=" + pue
          + ", which is physically impossible (a datacenter cannot deliver more energy"
          + " to its servers than it draws). Fix gatewai.providers." + name + ".pue.");
    }
  }
}
