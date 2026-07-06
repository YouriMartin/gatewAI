package io.github.yourimartin.gatewai.infrastructure.carbon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ElectricityMapsCarbonIntensityProviderTest {

  private static final String LATEST_URL =
      "https://api.electricitymap.org/v3/carbon-intensity/latest?zone=FR";
  private static final double FALLBACK = 230.0;

  private CarbonProperties properties;
  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private ElectricityMapsCarbonIntensityProvider provider;

  @BeforeEach
  void setUp() {
    properties = new CarbonProperties();
    properties.setGridIntensityGramsPerKwh(FALLBACK);
    properties.getElectricityMaps().setZone("FR");
    properties.getElectricityMaps().setToken("secret-token");

    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();

    provider = new ElectricityMapsCarbonIntensityProvider(
        builder, properties, new StaticCarbonIntensityProvider(properties));
  }

  @Test
  void returnsLiveIntensityWithZoneAndAuthToken() {
    server.expect(requestTo(LATEST_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("auth-token", "secret-token"))
        .andRespond(withSuccess(
            "{\"zone\":\"FR\",\"carbonIntensity\":56.0,\"updatedAt\":\"now\"}",
            MediaType.APPLICATION_JSON));

    assertEquals(56.0, provider.gramsCo2PerKwh());
    server.verify();
  }

  @Test
  void fallsBackToStaticOnApiError() {
    server.expect(requestTo(LATEST_URL))
        .andRespond(withServerError());

    assertEquals(FALLBACK, provider.gramsCo2PerKwh());
    server.verify();
  }

  @Test
  void fallsBackToStaticOnNonPositiveIntensity() {
    server.expect(requestTo(LATEST_URL))
        .andRespond(withSuccess(
            "{\"zone\":\"FR\",\"carbonIntensity\":0.0}",
            MediaType.APPLICATION_JSON));

    assertEquals(FALLBACK, provider.gramsCo2PerKwh());
    server.verify();
  }
}
