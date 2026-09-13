package io.github.yourimartin.gatewai.infrastructure.carbon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Cloud region → grid zone resolution (v3 lot C.2). The behaviour that matters is
 * the failure mode: an unknown region must warn <em>once</em> and fall back, never
 * throw, because zero-config boot and a typo must both keep serving traffic.
 */
class PropertiesCloudRegionZonesTest {

  /** ElectricityMaps id shape, as a guard against a malformed table entry. */
  private static final Pattern ZONE_ID = Pattern.compile("[A-Z]{2}(-[A-Z0-9]+)*");

  private ListAppender<ILoggingEvent> logs;
  private ch.qos.logback.classic.Logger logger;

  @BeforeEach
  void captureLogs() {
    logger = ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getLogger(PropertiesCloudRegionZones.class);
    logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
    logs.stop();
  }

  @Test
  void mapsTheBuiltInCloudRegions() {
    PropertiesCloudRegionZones zones = zones(Map.of());

    assertThat(zones.zoneFor("us-east-1")).contains("US-MIDA-PJM");
    assertThat(zones.zoneFor("eu-west-1")).contains("IE");
    assertThat(zones.zoneFor("eu-central-1")).contains("DE");
    assertThat(zones.zoneFor("europe-west4")).contains("NL");
    assertThat(zones.zoneFor("swedencentral")).contains("SE-SE3");
    // Region ids are case-insensitive and tolerate surrounding whitespace.
    assertThat(zones.zoneFor("  US-East-1 ")).contains("US-MIDA-PJM");
  }

  @Test
  void configurationOverridesTheBuiltInTable() {
    PropertiesCloudRegionZones zones =
        zones(Map.of("us-east-1", "US-CAL-CISO", "my-private-dc", "FR"));

    assertThat(zones.zoneFor("us-east-1")).contains("US-CAL-CISO");
    // And extends it: an operator can attribute a region no release knows about.
    assertThat(zones.zoneFor("my-private-dc")).contains("FR");
    assertThat(logs.list).isEmpty();
  }

  @Test
  void aGridZoneIdIsAcceptedAsIsBecauseTheRegionPropertyTakesEither() {
    PropertiesCloudRegionZones zones = zones(Map.of());

    assertThat(zones.zoneFor("FR")).contains("FR");
    assertThat(zones.zoneFor("US-MIDA-PJM")).contains("US-MIDA-PJM");
    assertThat(zones.zoneFor("SE-SE3")).contains("SE-SE3");
    assertThat(logs.list).isEmpty();
  }

  @Test
  void anUnknownRegionWarnsOnceFallsBackAndDoesNotThrow() {
    PropertiesCloudRegionZones zones = zones(Map.of());

    assertThat(zones.zoneFor("us-east-9")).isEmpty();
    assertThat(zones.zoneFor("us-east-9")).isEmpty();
    // Same region, different casing and spacing: still one warning.
    assertThat(zones.zoneFor(" us-East-9 ")).isEmpty();

    List<ILoggingEvent> warnings = logs.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .toList();
    assertThat(warnings).hasSize(1);
    assertThat(warnings.getFirst().getFormattedMessage())
        .contains("us-east-9")
        .contains("gatewai.carbon.region-zones");
  }

  @Test
  void aZoneShapedValueIsTakenAtFaceValueEvenWhenNobodyKnowsIt() {
    PropertiesCloudRegionZones zones = zones(Map.of());

    // Documented limit: the gateway cannot tell a mistyped zone id from a zone it
    // has not heard of, so anything shaped like one is passed through. The stored
    // zone (lot C.5) is what makes a wrong one visible afterwards; the intensity
    // provider falls back to the default value either way.
    assertThat(zones.zoneFor("US-NOPE-1")).contains("US-NOPE-1");
    assertThat(logs.list).isEmpty();
  }

  @Test
  void aMissingRegionIsSilentlyEmptyBecauseDeclaringNoneIsLegitimate() {
    PropertiesCloudRegionZones zones = zones(Map.of());

    assertThat(zones.zoneFor(null)).isEmpty();
    assertThat(zones.zoneFor("  ")).isEmpty();
    assertThat(logs.list).isEmpty();
  }

  @Test
  void everyBuiltInZoneIdHasTheElectricityMapsShape() {
    PropertiesCloudRegionZones zones = zones(Map.of());

    // Existence was verified against /v3/zones on 2026-09-13 (see the class
    // javadoc); this guards the shape, which is what a typo breaks.
    List<String> regions = List.of("us-east-1", "us-east-2", "us-west-1", "us-west-2",
        "ca-central-1", "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1",
        "eu-north-1", "ap-northeast-1", "ap-southeast-1", "ap-southeast-2",
        "ap-south-1", "sa-east-1", "us-central1", "us-east4", "us-west1",
        "europe-west1", "europe-west2", "europe-west3", "europe-west4",
        "europe-west9", "europe-north1", "asia-northeast1", "asia-southeast1",
        "eastus", "eastus2", "westus2", "northeurope", "westeurope", "uksouth",
        "francecentral", "germanywestcentral", "swedencentral", "japaneast",
        "australiaeast");
    for (String region : regions) {
      String zone = zones.zoneFor(region).orElseThrow(
          () -> new AssertionError("built-in table lost region " + region));
      assertThat(ZONE_ID.matcher(zone).matches())
          .as("zone id %s for region %s", zone, region)
          .isTrue();
    }
  }

  private static PropertiesCloudRegionZones zones(Map<String, String> overrides) {
    CarbonProperties properties = new CarbonProperties();
    properties.setRegionZones(new LinkedHashMap<>(overrides));
    return new PropertiesCloudRegionZones(properties);
  }
}
