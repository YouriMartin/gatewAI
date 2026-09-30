package io.github.yourimartin.gatewai.infrastructure.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gatewai.cache")
class SemanticCacheProperties {

  private double similarityThreshold = 0.92;
  private int topK = 1;
  private long ttlMinutes;
  private boolean clientNamespacing = true;
  /**
   * Non-system messages above which a request bypasses the cache (ADR 0014). 3
   * keeps the first follow-up (user, assistant, user) cacheable.
   */
  private int maxHistoryMessages = 3;

  double getSimilarityThreshold() {
    return similarityThreshold;
  }

  void setSimilarityThreshold(double similarityThreshold) {
    this.similarityThreshold = similarityThreshold;
  }

  int getTopK() {
    return topK;
  }

  void setTopK(int topK) {
    this.topK = topK;
  }

  long getTtlMinutes() {
    return ttlMinutes;
  }

  void setTtlMinutes(long ttlMinutes) {
    this.ttlMinutes = ttlMinutes;
  }

  boolean isClientNamespacing() {
    return clientNamespacing;
  }

  void setClientNamespacing(boolean clientNamespacing) {
    this.clientNamespacing = clientNamespacing;
  }

  int getMaxHistoryMessages() {
    return maxHistoryMessages;
  }

  void setMaxHistoryMessages(int maxHistoryMessages) {
    this.maxHistoryMessages = maxHistoryMessages;
  }
}
