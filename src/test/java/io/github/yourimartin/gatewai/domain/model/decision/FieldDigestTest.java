package io.github.yourimartin.gatewai.domain.model.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class FieldDigestTest {

  @Test
  void anAbsentFieldIsNotAnEmptyOne() {
    assertNotEquals(FieldDigest.begin("v").field(null).hex(),
        FieldDigest.begin("v").field("").hex());
  }

  @Test
  void theVersionSeparatesTwoHashesOverTheSameFields() {
    assertNotEquals(FieldDigest.begin("a/v1").field("x").hex(),
        FieldDigest.begin("b/v1").field("x").hex());
  }

  @Test
  void theSameFieldsAreTheSameHash() {
    assertEquals(FieldDigest.begin("v").count(2).field("x").field("y").hex(),
        FieldDigest.begin("v").count(2).field("x").field("y").hex());
  }
}
