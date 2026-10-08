package io.github.yourimartin.gatewai.domain.model.routing;

/**
 * How the conversation a request belongs to bore on its routing (ADR 0015,
 * v4 A.3). Null on a first turn and on a pinned request: there, the last user
 * turn alone decided.
 *
 * <p>Kept apart from {@link DecisionReason}, which summarises what the
 * classifier did and stays comparable with v2/v3 decisions; this says what the
 * conversation did to the classifier's answer.
 */
public enum ConversationRouting {

  /**
   * The conversation was recorded and the last turn needed no more than its
   * tier: the recorded model was kept, even when the turn alone would have gone
   * lower.
   */
  STICKY,

  /**
   * The conversation was recorded, and the last turn needed a higher tier: it
   * moved up, and the record moved with it. It never comes back down.
   */
  UPGRADED,

  /**
   * No record — a conversation older than ADR 0015, past its retention, or whose
   * first turn was pinned. The floor is the first user message's tier, and the
   * decision becomes the record from now on.
   */
  FIRST_TURN_FLOOR
}
