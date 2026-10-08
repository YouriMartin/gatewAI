package io.github.yourimartin.gatewai.domain.model.routing;

/** Model tiers, cheapest first: declaration order is the order of capability. */
public enum ModelTier {

  LOCAL,
  CLOUD_ENTRY,
  CLOUD_PREMIUM;

  /** True when this tier is strictly above {@code other}. */
  public boolean isAbove(ModelTier other) {
    return compareTo(other) > 0;
  }

  /** The higher of two tiers (v4 A.3: a conversation's floor). */
  public static ModelTier max(ModelTier a, ModelTier b) {
    return a.isAbove(b) ? a : b;
  }
}
