package io.github.yourimartin.gatewai.infrastructure.carbon;

import io.github.yourimartin.gatewai.domain.port.out.CarbonIntensityProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real-time {@link CarbonIntensityProvider} backed by the ElectricityMaps API
 * (Phase 4.2). Activated only when {@code gatewai.carbon.electricity-maps
 * .enabled=true}; otherwise the {@link StaticCarbonIntensityProvider} is the
 * sole provider.
 *
 * <p>It is {@link Primary} when present, and falls back to the static provider
 * on any API error or invalid payload so emissions accounting never breaks.
 *
 * <p>The call is stateless (one HTTP request per invocation). A scheduled
 * refresh / cache to avoid per-request latency belongs to Phase 4.4; callers
 * should not assume this method is cheap.
 */
@Component
@Primary
@ConditionalOnProperty(
    prefix = "gatewai.carbon.electricity-maps",
    name = "enabled",
    havingValue = "true")
class ElectricityMapsCarbonIntensityProvider implements CarbonIntensityProvider {

  private static final Logger LOG =
      LoggerFactory.getLogger(ElectricityMapsCarbonIntensityProvider.class);

  private final RestClient restClient;
  private final CarbonProperties.ElectricityMaps config;
  private final CarbonIntensityProvider fallback;

  ElectricityMapsCarbonIntensityProvider(
      RestClient.Builder restClientBuilder,
      CarbonProperties properties,
      StaticCarbonIntensityProvider fallback) {
    this.config = properties.getElectricityMaps();
    this.restClient = restClientBuilder
        .baseUrl(config.getBaseUrl())
        .build();
    this.fallback = fallback;
  }

  @Override
  public double gramsCo2PerKwh() {
    return gramsCo2PerKwh(config.getZone());
  }

  @Override
  public double gramsCo2PerKwh(String zone) {
    try {
      ElectricityMapsResponse response = restClient.get()
          .uri(uri -> uri.path("/carbon-intensity/latest")
              .queryParam("zone", zone)
              .build())
          .header("auth-token", config.getToken())
          .retrieve()
          .body(ElectricityMapsResponse.class);

      if (response == null || response.carbonIntensity() <= 0) {
        LOG.warn("ElectricityMaps returned no usable intensity for zone {}, "
            + "falling back", zone);
        return fallback.gramsCo2PerKwh(zone);
      }
      return response.carbonIntensity();
    } catch (RestClientException e) {
      LOG.warn("ElectricityMaps call failed for zone {} ({}), falling back",
          zone, e.getMessage());
      return fallback.gramsCo2PerKwh(zone);
    }
  }
}
