package io.github.yourimartin.gatewai.domain.model.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelTierTest {

  @Test
  void tiersAreOrderedByCapability() {
    assertTrue(ModelTier.CLOUD_PREMIUM.isAbove(ModelTier.CLOUD_ENTRY));
    assertTrue(ModelTier.CLOUD_ENTRY.isAbove(ModelTier.LOCAL));
    assertFalse(ModelTier.LOCAL.isAbove(ModelTier.LOCAL));
  }

  @Test
  void maxIsTheHigherTierEitherWay() {
    assertEquals(ModelTier.CLOUD_PREMIUM, ModelTier.max(ModelTier.LOCAL, ModelTier.CLOUD_PREMIUM));
    assertEquals(ModelTier.CLOUD_PREMIUM, ModelTier.max(ModelTier.CLOUD_PREMIUM, ModelTier.LOCAL));
    assertEquals(ModelTier.LOCAL, ModelTier.max(ModelTier.LOCAL, ModelTier.LOCAL));
  }
}
