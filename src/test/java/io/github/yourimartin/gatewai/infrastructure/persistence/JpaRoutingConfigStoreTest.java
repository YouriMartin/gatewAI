package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.model.StoredRoutingConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaRoutingConfigStoreTest {

  private static final RoutingConfig RULES = new RoutingConfig(
      "embedding", 100, 500, List.of("refactor"), 0.25,
      List.of(new SemanticRoute("chat", ModelTier.LOCAL, List.of("hello"))));

  private static final RoutingConfig OTHER_RULES = new RoutingConfig(
      "cascade", 10, 20, List.of("audit"), 0.9,
      List.of(new SemanticRoute("code", ModelTier.CLOUD_PREMIUM,
          List.of("refactor this"))));

  @Mock
  private SpringDataRoutingConfigRepository repository;

  @InjectMocks
  private JpaRoutingConfigStore store;

  @Test
  void loadMapsTheStoredRow() {
    when(repository.findById(RoutingConfigEntity.ROW_ID))
        .thenReturn(Optional.of(entity(RULES, 0.02, 4)));

    StoredRoutingConfig stored = store.load().orElseThrow();

    assertEquals(4, stored.revision());
    assertEquals(RULES, stored.config());
    assertEquals(0.02, stored.cascadeMarginBand());
  }

  @Test
  void loadIsEmptyBeforeAnyNodeHasWritten() {
    when(repository.findById(RoutingConfigEntity.ROW_ID))
        .thenReturn(Optional.empty());

    assertTrue(store.load().isEmpty());
  }

  @Test
  void seedInsertsTheRulesAndReturnsWhatIsStored() {
    when(repository.insertIfAbsent(anyString(), anyInt(), anyInt(), anyString(),
        anyDouble(), anyString(), anyDouble(), any(Instant.class)))
        .thenReturn(1);
    when(repository.findById(RoutingConfigEntity.ROW_ID))
        .thenReturn(Optional.of(entity(RULES, 0.02, 1)));

    StoredRoutingConfig stored = store.seedIfAbsent(RULES, 0.02);

    verify(repository).insertIfAbsent(eq("embedding"), eq(100), eq(500),
        anyString(), eq(0.25), anyString(), eq(0.02), any(Instant.class));
    assertEquals(1, stored.revision());
    assertEquals(RULES, stored.config());
  }

  @Test
  void seedReturnsTheWinnersRowWhenTheInsertWasANoOp() {
    // ON CONFLICT DO NOTHING: another node inserted first, so the read that
    // follows must return their rules rather than the ones we tried to write.
    when(repository.insertIfAbsent(anyString(), anyInt(), anyInt(), anyString(),
        anyDouble(), anyString(), anyDouble(), any(Instant.class)))
        .thenReturn(0);
    when(repository.findById(RoutingConfigEntity.ROW_ID))
        .thenReturn(Optional.of(entity(OTHER_RULES, 0.5, 1)));

    StoredRoutingConfig stored = store.seedIfAbsent(RULES, 0.02);

    assertEquals(OTHER_RULES, stored.config());
    assertEquals(0.5, stored.cascadeMarginBand());
  }

  @Test
  void saveConfigBumpsTheRevisionAndLeavesTheBandAlone() {
    RoutingConfigEntity row = entity(RULES, 0.02, 4);
    when(repository.findForUpdate()).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    StoredRoutingConfig stored = store.saveConfig(OTHER_RULES);

    assertEquals(5, stored.revision());
    assertEquals(OTHER_RULES, stored.config());
    assertEquals(0.02, stored.cascadeMarginBand());
  }

  @Test
  void saveCascadeMarginBandBumpsTheRevisionAndLeavesTheRulesAlone() {
    RoutingConfigEntity row = entity(RULES, 0.02, 4);
    when(repository.findForUpdate()).thenReturn(Optional.of(row));
    when(repository.saveAndFlush(row)).thenReturn(row);

    StoredRoutingConfig stored = store.saveCascadeMarginBand(0.08);

    assertEquals(5, stored.revision());
    assertEquals(RULES, stored.config());
    assertEquals(0.08, stored.cascadeMarginBand());
  }

  @Test
  void aWriteAgainstAMissingRowFailsRatherThanInventingHalfAConfig() {
    when(repository.findForUpdate()).thenReturn(Optional.empty());

    assertThrows(IllegalStateException.class, () -> store.saveConfig(RULES));
    verify(repository, never()).saveAndFlush(any());
  }

  private static RoutingConfigEntity entity(RoutingConfig config, double band,
                                            long revision) {
    RoutingConfigEntity entity = new RoutingConfigEntity();
    entity.applyConfig(config);
    entity.applyCascadeMarginBand(band);
    for (long i = 0; i < revision; i++) {
      entity.nextRevision(Instant.now());
    }
    return entity;
  }
}
