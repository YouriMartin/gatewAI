package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.routing.ClassificationOutcome;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationAffinity;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationOpening;
import io.github.yourimartin.gatewai.domain.model.routing.ConversationRouting;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ComplexityClassifier;
import io.github.yourimartin.gatewai.domain.port.out.ConversationAffinityStore;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * Where a routed request goes once its conversation is taken into account
 * (ADR 0015, v4 A.3).
 *
 * <p>A conversation keeps its model unless a later turn needs a higher tier.
 * The classifier still decides on the last user turn alone — that is what
 * keeps its metrics comparable — and the conversation then sets a floor under
 * its answer:
 *
 * <ul>
 *   <li>a <b>recorded</b> conversation never goes below its recorded tier, and
 *       an unchanged tier keeps the recorded <em>model</em>, because a different
 *       model of the same tier still forfeits the provider's prompt cache on the
 *       whole history ({@link ConversationRouting#STICKY});</li>
 *   <li>a turn that needs more moves the conversation up, for good
 *       ({@link ConversationRouting#UPGRADED});</li>
 *   <li>an <b>unrecorded</b> conversation is floored at its first user message's
 *       tier, and recorded from there ({@link ConversationRouting#FIRST_TURN_FLOOR}).
 *       Recording it is what keeps a later easy turn from dropping back below an
 *       earlier hard one.</li>
 * </ul>
 *
 * <p>Never fails a request: a store that cannot be read is a conversation with
 * no record, and one that cannot be written is a record that will be missing
 * next turn. Both degrade to the first-turn floor, never to an error.
 */
@Component
class ConversationStickiness {

  private static final Logger LOG =
      LoggerFactory.getLogger(ConversationStickiness.class);

  private final ConversationAffinityStore store;
  private final ModelRegistry modelRegistry;
  private final ComplexityClassifier classifier;

  ConversationStickiness(ConversationAffinityStore store, ModelRegistry modelRegistry,
                         ComplexityClassifier classifier) {
    this.store = store;
    this.modelRegistry = modelRegistry;
    this.classifier = classifier;
  }

  /** The request's messages, as the conversation fingerprint reads them. */
  static List<ConversationOpening.Turn> turns(Prompt prompt) {
    return prompt.getInstructions().stream()
        .map(ConversationStickiness::turn)
        .toList();
  }

  private static ConversationOpening.Turn turn(Message message) {
    return new ConversationOpening.Turn(message.getMessageType().getValue(),
        message.getText());
  }

  /**
   * Places a routed request.
   *
   * @param turns    the request's messages
   * @param userText the last user turn, already classified into {@code lastTurn}
   * @param lastTurn the classifier's answer for that turn
   */
  Placement place(List<ConversationOpening.Turn> turns, String userText,
                  ClassificationOutcome lastTurn) {
    ModelTier classified = lastTurn.tier();
    Optional<ConversationOpening> opening = ConversationOpening.ofHistory(turns);
    if (opening.isEmpty()) {
      return new Placement(classified, firstOf(classified), null, null, null);
    }

    String fingerprint = opening.get().fingerprint();
    ConversationAffinity recorded = find(fingerprint);
    Placement placement;
    if (recorded == null) {
      ModelTier tier = ModelTier.max(classified,
          firstTurnTier(opening.get(), userText, classified));
      placement = new Placement(tier, firstOf(tier),
          ConversationRouting.FIRST_TURN_FLOOR, classified, fingerprint);
    } else if (classified.isAbove(recorded.tier())) {
      placement = new Placement(classified, firstOf(classified),
          ConversationRouting.UPGRADED, classified, fingerprint);
    } else {
      placement = new Placement(recorded.tier(), recordedModel(recorded),
          ConversationRouting.STICKY, classified, fingerprint);
    }

    if (placement.model() != null) {
      save(fingerprint, placement.model().modelId(), placement.tier());
    }
    return placement;
  }

  /**
   * Records the conversation a routed first turn opens, once its answer is
   * known. Does nothing for a request that already has history, or an empty
   * answer — there is no opening to recognise.
   */
  void recordFirstTurn(List<ConversationOpening.Turn> turns, String answer,
                       ModelDefinition model) {
    ConversationOpening.ofFirstTurn(turns, answer).ifPresent(opening ->
        save(opening.fingerprint(), model.modelId(), model.tier()));
  }

  /**
   * The first user message's tier. Classified only when it differs from the
   * last turn, so a conversation whose only user message is the last one (an
   * assistant greeting first) costs no second classification.
   */
  private ModelTier firstTurnTier(ConversationOpening opening, String userText,
                                  ModelTier classified) {
    String firstUser = opening.firstUserText();
    if (firstUser == null || firstUser.isBlank() || firstUser.equals(userText)) {
      return classified;
    }
    return classifier.classify(firstUser).tier();
  }

  /**
   * The recorded model, while the registry still declares it on the recorded
   * tier; otherwise that tier's first model. A configuration change moves a
   * conversation within its tier, never off it.
   */
  private ModelDefinition recordedModel(ConversationAffinity recorded) {
    return modelRegistry.findByModelId(recorded.modelId())
        .filter(model -> model.tier() == recorded.tier())
        .orElseGet(() -> firstOf(recorded.tier()));
  }

  private ModelDefinition firstOf(ModelTier tier) {
    List<ModelDefinition> candidates = modelRegistry.findByTier(tier);
    return candidates.isEmpty() ? null : candidates.getFirst();
  }

  private ConversationAffinity find(String fingerprint) {
    try {
      return store.find(fingerprint).orElse(null);
    } catch (RuntimeException e) {
      LOG.warn("Could not read the conversation record, flooring on the first turn: {}",
          e.toString());
      return null;
    }
  }

  private void save(String fingerprint, String modelId, ModelTier tier) {
    try {
      store.record(fingerprint, modelId, tier);
    } catch (RuntimeException e) {
      LOG.warn("Could not record the conversation's model: {}", e.toString());
    }
  }

  /**
   * Where the request goes.
   *
   * @param tier           the tier it is sent to
   * @param model          the model, or null when the registry has none for the tier
   * @param routing        how the conversation bore on it, null on a first turn
   * @param classifiedTier the last turn's own tier, null on a first turn
   * @param fingerprint    the conversation's opening hash, null on a first turn
   */
  record Placement(ModelTier tier, ModelDefinition model, ConversationRouting routing,
                   ModelTier classifiedTier, String fingerprint) {
  }
}
