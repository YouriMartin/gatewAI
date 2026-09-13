package io.github.yourimartin.gatewai.infrastructure.carbon;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Carbon-model configuration.
 *
 * <p>{@code gridIntensityGramsPerKwh} is the static regional fallback used by
 * the default provider. When {@code electricityMaps.enabled} is {@code true},
 * a real-time provider (Phase 4.2) overrides it and falls back to this value
 * when the API is unreachable.
 */
@ConfigurationProperties(prefix = "gatewai.carbon")
class CarbonProperties {

  /** Electricity grid carbon intensity, in gCO2-equivalent per kWh. */
  private double gridIntensityGramsPerKwh = 230.0;

  /**
   * Per-zone static intensities (zone id → gCO2/kWh) used by the default
   * provider for carbon-aware geo routing when no live API is configured.
   */
  private Map<String, Double> zoneIntensities = new LinkedHashMap<>();

  /**
   * Cloud region → grid zone overrides (v3 lot C.2), e.g.
   * {@code gatewai.carbon.region-zones.us-east-1=US-MIDA-PJM}. Takes precedence
   * over the built-in table, and is how an unmapped region gets attributed
   * without waiting for a release.
   */
  private Map<String, String> regionZones = new LinkedHashMap<>();

  private ElectricityMaps electricityMaps = new ElectricityMaps();

  double getGridIntensityGramsPerKwh() {
    return gridIntensityGramsPerKwh;
  }

  void setGridIntensityGramsPerKwh(double gridIntensityGramsPerKwh) {
    this.gridIntensityGramsPerKwh = gridIntensityGramsPerKwh;
  }

  Map<String, Double> getZoneIntensities() {
    return zoneIntensities;
  }

  void setZoneIntensities(Map<String, Double> zoneIntensities) {
    this.zoneIntensities = zoneIntensities;
  }

  Map<String, String> getRegionZones() {
    return regionZones;
  }

  void setRegionZones(Map<String, String> regionZones) {
    this.regionZones = regionZones;
  }

  ElectricityMaps getElectricityMaps() {
    return electricityMaps;
  }

  void setElectricityMaps(ElectricityMaps electricityMaps) {
    this.electricityMaps = electricityMaps;
  }

  /** Settings for the ElectricityMaps real-time carbon-intensity API. */
  static class ElectricityMaps {

    private boolean enabled;
    private String baseUrl = "https://api.electricitymap.org/v3";
    private String zone = "FR";
    private String token = "";

    boolean isEnabled() {
      return enabled;
    }

    void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    String getBaseUrl() {
      return baseUrl;
    }

    void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    String getZone() {
      return zone;
    }

    void setZone(String zone) {
      this.zone = zone;
    }

    String getToken() {
      return token;
    }

    void setToken(String token) {
      this.token = token;
    }
  }
}
