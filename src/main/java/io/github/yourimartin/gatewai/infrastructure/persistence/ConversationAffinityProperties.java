package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Conversation-sticky routing: how long a conversation is remembered (ADR 0015). */
@ConfigurationProperties(prefix = "gatewai.routing")
class ConversationAffinityProperties {

  /**
   * How long a conversation keeps its model after its last turn. Past it, the
   * record is ignored and then purged, and the next turn falls back to its
   * first-turn floor.
   */
  private Duration conversationTtl = Duration.ofHours(24);

  /** How often expired records are purged, in milliseconds. Hourly by default. */
  private long conversationPurgeIntervalMs = 3_600_000L;

  Duration getConversationTtl() {
    return conversationTtl;
  }

  void setConversationTtl(Duration conversationTtl) {
    this.conversationTtl = conversationTtl;
  }

  long getConversationPurgeIntervalMs() {
    return conversationPurgeIntervalMs;
  }

  void setConversationPurgeIntervalMs(long conversationPurgeIntervalMs) {
    this.conversationPurgeIntervalMs = conversationPurgeIntervalMs;
  }
}
