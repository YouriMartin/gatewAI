package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigPort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoutingConfigServiceTest {

  @Mock
  private RoutingConfigPort port;

  private RoutingConfigService service;

  @BeforeEach
  void setUp() {
    service = new RoutingConfigService(port);
  }

  @Test
  void currentDelegatesToPort() {
    RoutingConfig config =
        new RoutingConfig("heuristic", 100, 500, List.of("refactor"));
    when(port.get()).thenReturn(config);

    assertEquals(config, service.current());
  }

  @Test
  void updateAppliesValidConfig() {
    RoutingConfig config = new RoutingConfig("llm", 100, 500, List.of());

    service.update(config);

    verify(port).update(config);
  }

  @Test
  void strategyIsCaseInsensitive() {
    service.update(new RoutingConfig("LLM", 0, 0, List.of()));

    verify(port).update(any());
  }

  @Test
  void rejectsUnknownStrategy() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(new RoutingConfig("magic", 100, 500, List.of())));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsNegativeThreshold() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(new RoutingConfig("heuristic", -1, 500, List.of())));
    verify(port, never()).update(any());
  }

  @Test
  void rejectsEntryThresholdAbovePremium() {
    assertThrows(IllegalArgumentException.class, () ->
        service.update(new RoutingConfig("heuristic", 600, 500, List.of())));
    verify(port, never()).update(any());
  }
}
