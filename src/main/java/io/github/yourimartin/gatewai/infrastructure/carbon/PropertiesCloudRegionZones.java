package io.github.yourimartin.gatewai.infrastructure.carbon;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import io.github.yourimartin.gatewai.domain.port.out.CloudRegionZones;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps a cloud region id to the electricity grid zone that serves it
 * (v3 lot C.2), with configuration winning over the built-in table.
 *
 * <p>Resolution order, first hit wins:
 * <ol>
 *   <li>{@code gatewai.carbon.region-zones.<region>} — the operator's own mapping,
 *       which is how a region this table has never heard of gets attributed;</li>
 *   <li>the built-in table below;</li>
 *   <li>the input itself, when it already looks like a grid zone id
 *       ({@code FR}, {@code US-MIDA-PJM}) — the {@code region} property accepts
 *       either, so a direct API declared as {@code region=US} needs no mapping;</li>
 *   <li>nothing: an unrecognised region is logged <b>once</b> and returns empty, so
 *       the caller falls back to the gateway's default zone. Never an exception —
 *       a typo must not stop a gateway from serving traffic.</li>
 * </ol>
 *
 * <p><b>The table is an approximation, and deliberately a small one.</b> A cloud
 * region is a metro area; a grid zone is a balancing authority. Each entry names
 * the authority that predominantly serves that region's known datacenter sites,
 * which is the best available attribution without the provider publishing one.
 * Every zone id was verified against the ElectricityMaps zone list
 * (<a href="https://api.electricitymap.org/v3/zones">/v3/zones</a>, 350 zones) on
 * <b>2026-09-13</b>; see {@code docs/technical/green-accounting.md} for the table
 * with its per-entry reasoning.
 */
@Component
class PropertiesCloudRegionZones implements CloudRegionZones {

  private static final Logger LOG = LoggerFactory.getLogger(PropertiesCloudRegionZones.class);

  /** ElectricityMaps zone ids: an ISO country code, optionally with subzones. */
  private static final Pattern ZONE_ID = Pattern.compile("[A-Z]{2}(-[A-Z0-9]+)*");

  /**
   * Built-in cloud region → grid zone table, keys lowercased. Overridable per
   * entry; extended by configuration rather than by a release.
   */
  private static final Map<String, String> BUILT_IN = Map.ofEntries(
      // AWS
      Map.entry("us-east-1", "US-MIDA-PJM"),       // N. Virginia — PJM
      Map.entry("us-east-2", "US-MIDA-PJM"),       // Ohio — PJM
      Map.entry("us-west-1", "US-CAL-CISO"),       // N. California — CAISO
      Map.entry("us-west-2", "US-NW-PACW"),        // Oregon — PacifiCorp West
      Map.entry("ca-central-1", "CA-QC"),          // Montreal — Hydro-Québec
      Map.entry("eu-west-1", "IE"),                // Ireland
      Map.entry("eu-west-2", "GB"),                // London
      Map.entry("eu-west-3", "FR"),                // Paris
      Map.entry("eu-central-1", "DE"),             // Frankfurt
      Map.entry("eu-north-1", "SE-SE3"),           // Stockholm — SE3 bidding zone
      Map.entry("ap-northeast-1", "JP-TK"),        // Tokyo — TEPCO area
      Map.entry("ap-southeast-1", "SG"),           // Singapore
      Map.entry("ap-southeast-2", "AU-NSW"),       // Sydney — New South Wales
      Map.entry("ap-south-1", "IN-WE"),            // Mumbai — Western India
      Map.entry("sa-east-1", "BR-CS"),             // São Paulo — Central Brazil
      // Google Cloud
      Map.entry("us-central1", "US-MIDW-MISO"),    // Iowa — MISO
      Map.entry("us-east4", "US-MIDA-PJM"),        // N. Virginia — PJM
      Map.entry("us-west1", "US-NW-PACW"),         // Oregon — PacifiCorp West
      Map.entry("europe-west1", "BE"),             // Belgium
      Map.entry("europe-west2", "GB"),             // London
      Map.entry("europe-west3", "DE"),             // Frankfurt
      Map.entry("europe-west4", "NL"),             // Netherlands
      Map.entry("europe-west9", "FR"),             // Paris
      Map.entry("europe-north1", "FI"),            // Finland
      Map.entry("asia-northeast1", "JP-TK"),       // Tokyo
      Map.entry("asia-southeast1", "SG"),          // Singapore
      // Azure
      Map.entry("eastus", "US-MIDA-PJM"),          // Virginia — PJM
      Map.entry("eastus2", "US-MIDA-PJM"),         // Virginia — PJM
      Map.entry("westus2", "US-NW-BPAT"),          // Washington — Bonneville
      Map.entry("northeurope", "IE"),              // Ireland
      Map.entry("westeurope", "NL"),               // Netherlands
      Map.entry("uksouth", "GB"),                  // London
      Map.entry("francecentral", "FR"),            // Paris
      Map.entry("germanywestcentral", "DE"),       // Frankfurt
      Map.entry("swedencentral", "SE-SE3"),        // Gävle — SE3 bidding zone
      Map.entry("japaneast", "JP-TK"),             // Tokyo
      Map.entry("australiaeast", "AU-NSW"));       // Sydney

  /** Regions already reported as unknown; keeps the warning to one per region. */
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  private final CarbonProperties properties;

  PropertiesCloudRegionZones(CarbonProperties properties) {
    this.properties = properties;
  }

  @Override
  public Optional<String> zoneFor(String region) {
    if (region == null || region.isBlank()) {
      return Optional.empty();
    }
    String trimmed = region.trim();
    String key = trimmed.toLowerCase(Locale.ROOT);

    String configured = configuredZone(key);
    if (configured != null) {
      return Optional.of(configured);
    }
    String builtIn = BUILT_IN.get(key);
    if (builtIn != null) {
      return Optional.of(builtIn);
    }
    if (ZONE_ID.matcher(trimmed).matches()) {
      // Already a grid zone id — the region property accepts either form.
      return Optional.of(trimmed);
    }
    warnOnce(trimmed);
    return Optional.empty();
  }

  private String configuredZone(String key) {
    for (Map.Entry<String, String> override : properties.getRegionZones().entrySet()) {
      if (override.getKey() != null
          && override.getKey().toLowerCase(Locale.ROOT).equals(key)
          && override.getValue() != null && !override.getValue().isBlank()) {
        return override.getValue().trim();
      }
    }
    return null;
  }

  private void warnOnce(String region) {
    if (warned.add(region.toLowerCase(Locale.ROOT))) {
      LOG.warn("Unknown cloud region '{}': no grid zone mapping, falling back to the "
          + "gateway's default zone. Map it with gatewai.carbon.region-zones.{}=<zone id> "
          + "(ElectricityMaps zone, e.g. US-MIDA-PJM).", region, region);
    }
  }
}
