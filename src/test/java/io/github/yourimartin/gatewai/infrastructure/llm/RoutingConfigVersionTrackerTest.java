package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutingConfigVersionTrackerTest {

  private RoutingConfigPort port;
  private MeterRegistry registry;
  private RoutingConfigVersionTracker tracker;

  @BeforeEach
  void setUp() {
    port = mock(RoutingConfigPort.class);
    registry = new SimpleMeterRegistry();
    tracker = new RoutingConfigVersionTracker(port, registry);
  }

  @Test
  @DisplayName("an unchanged configuration is the same version, and no change")
  void stableConfigDoesNotCount() {
    when(port.get()).thenReturn(config(0.60));

    assertEquals(tracker.current(), tracker.current());
    assertEquals(0.0, changes());
  }

  @Test
  @DisplayName("an edit changes the version and is counted (v2 batch 6)")
  void editsAreCounted() {
    when(port.get()).thenReturn(config(0.60), config(0.45), config(0.45));

    String before = tracker.current();
    String after = tracker.current();
    tracker.current();

    assertNotEquals(before, after);
    assertEquals(1.0, changes(),
        "the counter is what lets a tier-mix shift be attributed to an edit "
            + "— or shown to have none, which means the traffic drifted");
  }

  @Test
  @DisplayName("the counter exists before the first edit, so the panel has a series")
  void counterIsRegisteredUpFront() {
    assertEquals(0.0, changes());
  }

  private double changes() {
    return registry.get("gatewai.routing.config.changes").counter().count();
  }

  private static RoutingConfig config(double similarityThreshold) {
    return new RoutingConfig("embedding", 100, 500, List.of("refactor"),
        similarityThreshold,
        List.of(new SemanticRoute("casual-chat", ModelTier.LOCAL,
            List.of("hello"))));
  }
}
