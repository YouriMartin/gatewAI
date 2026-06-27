package com.example.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.example.gatewai.domain.model.RoutingConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClassifierRoutingConfigAdapterTest {

  private ClassifierProperties properties;
  private ClassifierRoutingConfigAdapter adapter;

  @BeforeEach
  void setUp() {
    properties = new ClassifierProperties();
    adapter = new ClassifierRoutingConfigAdapter(properties);
  }

  @Test
  void getReflectsCurrentProperties() {
    RoutingConfig config = adapter.get();

    assertEquals("heuristic", config.strategy());
    assertEquals(100, config.entryLengthThreshold());
    assertEquals(500, config.premiumLengthThreshold());
    assertTrue(config.premiumKeywords().contains("refactor"));
  }

  @Test
  void updateMutatesPropertiesLive() {
    adapter.update(new RoutingConfig("llm", 50, 800, List.of("foo", "bar")));

    assertEquals(ClassifierStrategy.LLM, properties.getStrategy());
    assertEquals(50, properties.getEntryLengthThreshold());
    assertEquals(800, properties.getPremiumLengthThreshold());
    assertEquals(List.of("foo", "bar"), properties.getPremiumKeywords());
  }
}
