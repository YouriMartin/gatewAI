package io.github.yourimartin.gatewai.infrastructure.llm;

import static io.github.yourimartin.gatewai.infrastructure.llm.ClassificationOutcomeFixtures.outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.InMemoryConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationOpening;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationRouting;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The floor rules of ADR 0015 that the advisor-level test does not reach. */
class ConversationStickinessTest {

  private static final ModelDefinition ENTRY = model("entry-1", ModelTier.CLOUD_ENTRY);
  private static final ModelDefinition ENTRY_2 = model("entry-2", ModelTier.CLOUD_ENTRY);

  private static final List<ConversationOpening.Turn> HISTORY = List.of(
      turn("user", "Explain CAP"), turn("assistant", "CAP says..."), turn("user", "ok"));

  private InMemoryConversationAffinityStore store;
  private ModelRegistry registry;
  private ComplexityClassifier classifier;
  private ConversationStickiness stickiness;

  @BeforeEach
  void setUp() {
    store = new InMemoryConversationAffinityStore();
    registry = mock(ModelRegistry.class);
    classifier = mock(ComplexityClassifier.class);
    stickiness = new ConversationStickiness(store, registry, classifier);
  }

  @Test
  void aFirstTurnIsPlacedOnItsOwnTierWithNoConversationFields() {
    when(registry.findByTier(ModelTier.CLOUD_ENTRY)).thenReturn(List.of(ENTRY));

    ConversationStickiness.Placement placement = stickiness.place(
        List.of(turn("user", "Explain CAP")), "Explain CAP", outcome(ModelTier.CLOUD_ENTRY));

    assertEquals(ENTRY, placement.model());
    assertNull(placement.routing());
    assertNull(placement.fingerprint());
    assertTrue(store.records().isEmpty(), "a first turn is recorded after its answer, not before");
  }

  @Test
  void aRecordedModelNoLongerOnItsTierFallsBackToTheTiersFirstModel() {
    String fingerprint = ConversationOpening.ofHistory(HISTORY).orElseThrow().fingerprint();
    store.record(fingerprint, "removed-model", ModelTier.CLOUD_ENTRY);
    when(registry.findByModelId("removed-model")).thenReturn(Optional.empty());
    when(registry.findByTier(ModelTier.CLOUD_ENTRY)).thenReturn(List.of(ENTRY_2, ENTRY));

    ConversationStickiness.Placement placement =
        stickiness.place(HISTORY, "ok", outcome(ModelTier.LOCAL));

    assertEquals(ConversationRouting.STICKY, placement.routing());
    assertEquals(ENTRY_2, placement.model(), "the conversation must stay on its tier");
  }

  @Test
  void anAssistantGreetingFirstCostsNoSecondClassification() {
    // The only user message is the last one: the first turn is the last turn.
    List<ConversationOpening.Turn> greeting = List.of(
        turn("assistant", "Hi, how can I help?"), turn("user", "Explain CAP"));
    when(registry.findByTier(ModelTier.CLOUD_ENTRY)).thenReturn(List.of(ENTRY));

    ConversationStickiness.Placement placement =
        stickiness.place(greeting, "Explain CAP", outcome(ModelTier.CLOUD_ENTRY));

    assertEquals(ConversationRouting.FIRST_TURN_FLOOR, placement.routing());
    verify(classifier, never()).classify(any());
  }

  @Test
  void noModelForTheTierIsAPassThroughAndRecordsNothing() {
    when(classifier.classify("Explain CAP")).thenReturn(outcome(ModelTier.CLOUD_ENTRY));
    when(registry.findByTier(ModelTier.CLOUD_ENTRY)).thenReturn(List.of());

    ConversationStickiness.Placement placement =
        stickiness.place(HISTORY, "ok", outcome(ModelTier.LOCAL));

    assertNull(placement.model());
    assertEquals(ModelTier.CLOUD_ENTRY, placement.tier());
    assertTrue(store.records().isEmpty());
  }

  @Test
  void anEmptyFirstAnswerRecordsNothing() {
    stickiness.recordFirstTurn(List.of(turn("user", "Explain CAP")), "", ENTRY);
    assertTrue(store.records().isEmpty());
  }

  private static ConversationOpening.Turn turn(String role, String text) {
    return new ConversationOpening.Turn(role, text);
  }

  private static ModelDefinition model(String id, ModelTier tier) {
    return new ModelDefinition(id, "provider", id, 0.0, EnergyProfile.NOT_ACCOUNTED, tier);
  }
}
