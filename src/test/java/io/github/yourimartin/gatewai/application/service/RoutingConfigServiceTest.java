package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoutingConfigServiceTest {

  private static final List<SemanticRoute> ROUTES = List.of(
      new SemanticRoute("chat", ModelTier.LOCAL, List.of("hello")),
      new SemanticRoute("code", ModelTier.CLOUD_PREMIUM, List.of("refactor")));

  @Mock
  private RoutingConfigPort port;

  private RoutingConfigService service;

  @BeforeEach
  void setUp() {
    service = new RoutingConfigService(port);
  }

  private static RoutingConfig config(String strategy,
                                      List<SemanticRoute> routes) {
    return new RoutingConfig(strategy, 100, 500, List.of("refactor"),
        0.6, routes);
  }

  @Test
  void currentDelegatesToPort() {
    RoutingConfig config = config("heuristic", ROUTES);
    when(port.get()).thenReturn(config);

    assertEquals(config, service.current());
  }

  @Test
  void updateAppliesValidConfig() {
    RoutingConfig config = config("llm", ROUTES);

    service.update(config);

    verify(port).update(config);
  }

  @Test
  void acceptsEmbeddingStrategyWithRoutes() {
    service.update(config("embedding", ROUTES));

    verify(port).update(any());
  }

  @Test
  void strategyIsCaseInsensitive() {
    service.update(config("LLM", ROUTES));

    verify(port).update(any());
  }

  @Test
  void rejectsUnknownStrategy() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(config("magic", ROUTES)));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsNegativeThreshold() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(new RoutingConfig("heuristic", -1, 500, List.of(),
            0.6, ROUTES)));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsEntryThresholdAbovePremium() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(new RoutingConfig("heuristic", 600, 500, List.of(),
            0.6, ROUTES)));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsSimilarityThresholdOutsideUnitRange() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(new RoutingConfig("embedding", 100, 500, List.of(),
            1.2, ROUTES)));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsEmbeddingStrategyWithoutRoutes() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(config("embedding", List.of())));
    verify(port, never()).update(any());
  }

  @Test
  void heuristicStrategyAllowsEmptyRoutes() {
    service.update(config("heuristic", List.of()));

    verify(port).update(any());
  }

  @Test
  void rejectsBlankRouteName() {
    List<SemanticRoute> routes = List.of(
        new SemanticRoute("  ", ModelTier.LOCAL, List.of("hello")));

    assertThrows(IllegalArgumentException.class, () ->
        service.update(config("embedding", routes)));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsDuplicateRouteNames() {
    List<SemanticRoute> routes = List.of(
        new SemanticRoute("chat", ModelTier.LOCAL, List.of("hello")),
        new SemanticRoute("chat", ModelTier.CLOUD_ENTRY, List.of("hi")));

    assertThrows(IllegalArgumentException.class, () ->
        service.update(config("embedding", routes)));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsRouteWithoutTier() {
    List<SemanticRoute> routes = List.of(
        new SemanticRoute("chat", null, List.of("hello")));

    assertThrows(IllegalArgumentException.class, () ->
        service.update(config("embedding", routes)));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsRouteWithoutExamples() {
    List<SemanticRoute> routes = List.of(
        new SemanticRoute("chat", ModelTier.LOCAL, List.of(" ")));

    assertThrows(IllegalArgumentException.class, () ->
        service.update(config("embedding", routes)));
    verify(port, never()).update(any());
  }
}
