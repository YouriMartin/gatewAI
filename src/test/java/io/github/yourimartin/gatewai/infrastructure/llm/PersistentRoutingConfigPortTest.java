package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.ModelTier;
import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.SemanticRoute;
import io.github.yourimartin.gatewai.domain.model.StoredRoutingConfig;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The multi-instance contract of the routing config (v3 lot B.1): what a node
 * adopts at startup, what it writes, and what it picks up from another node.
 */
class PersistentRoutingConfigPortTest {

  private static final RoutingConfig NODE_B_RULES = new RoutingConfig(
      "cascade", 42, 900, List.of("node-b"), 0.31,
      List.of(new SemanticRoute("from-node-b", ModelTier.CLOUD_ENTRY,
          List.of("an example only node B knows"))));

  private FakeStore store;
  private ClassifierProperties properties;
  private ClassifierRoutingConfigAdapter local;
  private PersistentRoutingConfigPort port;

  @BeforeEach
  void setUp() {
    store = new FakeStore();
    properties = new ClassifierProperties();
    local = new ClassifierRoutingConfigAdapter(properties);
    port = new PersistentRoutingConfigPort(local, store);
  }

  @Test
  void firstStartSeedsTheStoreFromTheLocalProperties() {
    RoutingConfig defaults = local.get();

    port.adoptStoredConfig();

    assertTrue(store.row.isPresent(), "the first node must write the row");
    assertEquals(defaults, store.row.get().config());
    assertEquals(properties.getCascadeMarginBand(),
        store.row.get().cascadeMarginBand());
    assertEquals(1, store.row.get().revision());
  }

  @Test
  void restartAdoptsTheStoredRulesRatherThanThePropertyDefaults() {
    store.row = Optional.of(
        new StoredRoutingConfig(7, NODE_B_RULES, 0.05, Instant.now()));

    port.adoptStoredConfig();

    assertEquals(NODE_B_RULES, port.get());
    assertEquals(0.05, port.cascadeMarginBand());
    // The classifier reads the properties bean, not the port, so the rules only
    // took effect if they landed there.
    assertEquals(42, properties.getEntryLengthThreshold());
    assertEquals("from-node-b", properties.getRoutes().getFirst().getName());
  }

  @Test
  void losingTheConcurrentFirstStartAdoptsTheWinnersRules() {
    // Another node inserted the row between our load and our seed: the store
    // returns what is there, not what we asked it to write.
    store.seedWinner =
        new StoredRoutingConfig(1, NODE_B_RULES, 0.07, Instant.now());

    port.adoptStoredConfig();

    assertEquals(NODE_B_RULES, port.get());
    assertEquals(0.07, port.cascadeMarginBand());
  }

  @Test
  void updateWritesThroughToTheStore() {
    port.adoptStoredConfig();

    port.update(NODE_B_RULES);

    assertEquals(NODE_B_RULES, store.row.orElseThrow().config());
    assertEquals(2, store.row.orElseThrow().revision());
    assertEquals(NODE_B_RULES, port.get());
  }

  @Test
  void anUpdateThatCannotBePersistedDoesNotTakeEffectLocally() {
    port.adoptStoredConfig();
    RoutingConfig before = port.get();
    store.failing = true;

    assertThrows(IllegalStateException.class, () -> port.update(NODE_B_RULES));

    assertEquals(before, port.get(),
        "a config that persisted nowhere must not route anywhere");
  }

  @Test
  void aBandEditDoesNotRevertAnotherNodesConfigEdit() {
    port.adoptStoredConfig();
    RoutingConfig ourStaleCopy = port.get();
    // Node B edited the rules; we have not polled yet, so our cached copy is old.
    store.row = Optional.of(new StoredRoutingConfig(
        2, NODE_B_RULES, store.row.orElseThrow().cascadeMarginBand(),
        Instant.now()));

    port.updateCascadeMarginBand(0.11);

    assertNotEquals(ourStaleCopy, store.row.orElseThrow().config());
    assertEquals(NODE_B_RULES, store.row.orElseThrow().config());
    assertEquals(0.11, store.row.orElseThrow().cascadeMarginBand());
    // And the write pulled node B's rules in on the way back.
    assertEquals(NODE_B_RULES, port.get());
  }

  @Test
  void syncPicksUpAnEditMadeOnAnotherNode() {
    port.adoptStoredConfig();
    store.row = Optional.of(
        new StoredRoutingConfig(9, NODE_B_RULES, 0.03, Instant.now()));

    port.sync();

    assertEquals(NODE_B_RULES, port.get());
    assertEquals(0.03, port.cascadeMarginBand());
    assertEquals(0.31, properties.getRouteSimilarityThreshold());
  }

  @Test
  void syncIgnoresARevisionItHasAlreadyApplied() {
    port.adoptStoredConfig();
    RoutingConfig applied = port.get();
    // Same revision, different payload: only a node that re-reads on every tick
    // instead of comparing revisions would pick this up.
    store.row = Optional.of(new StoredRoutingConfig(
        store.row.orElseThrow().revision(), NODE_B_RULES, 0.03, Instant.now()));

    port.sync();

    assertEquals(applied, port.get());
  }

  @Test
  void syncKeepsTheLastKnownRulesWhenTheStoreIsUnreachable() {
    port.adoptStoredConfig();
    RoutingConfig applied = port.get();
    store.failing = true;

    port.sync();

    assertEquals(applied, port.get());
  }

  /** In-memory stand-in with the store's column-scoped write semantics. */
  private static final class FakeStore implements RoutingConfigStore {

    private Optional<StoredRoutingConfig> row = Optional.empty();
    private StoredRoutingConfig seedWinner;
    private boolean failing;

    @Override
    public Optional<StoredRoutingConfig> load() {
      failFast();
      return row;
    }

    @Override
    public StoredRoutingConfig seedIfAbsent(RoutingConfig config,
                                            double cascadeMarginBand) {
      failFast();
      if (seedWinner != null) {
        row = Optional.of(seedWinner);
      } else if (row.isEmpty()) {
        row = Optional.of(
            new StoredRoutingConfig(1, config, cascadeMarginBand, Instant.now()));
      }
      return row.orElseThrow();
    }

    @Override
    public StoredRoutingConfig saveConfig(RoutingConfig config) {
      failFast();
      StoredRoutingConfig current = row.orElseThrow();
      row = Optional.of(new StoredRoutingConfig(current.revision() + 1, config,
          current.cascadeMarginBand(), Instant.now()));
      return row.orElseThrow();
    }

    @Override
    public StoredRoutingConfig saveCascadeMarginBand(double cascadeMarginBand) {
      failFast();
      StoredRoutingConfig current = row.orElseThrow();
      row = Optional.of(new StoredRoutingConfig(current.revision() + 1,
          current.config(), cascadeMarginBand, Instant.now()));
      return row.orElseThrow();
    }

    private void failFast() {
      if (failing) {
        throw new IllegalStateException("store unreachable");
      }
    }
  }
}
