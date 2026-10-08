package io.github.yourimartin.gatewai.domain.model.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CacheScopeTest {

  private static final List<CacheScope.Turn> PERSONA =
      List.of(new CacheScope.Turn("system", "Answer in English."));

  @Test
  void theSameContextIsTheSameScope() {
    assertEquals(CacheScope.of(PERSONA, "model-a", "user-1", List.of("\n")),
        CacheScope.of(List.of(new CacheScope.Turn("system", "Answer in English.")),
            "model-a", "user-1", List.of("\n")));
  }

  @Test
  void everySingleTurnRequestWithNothingElseSharesOneScope() {
    // What keeps the v2/v3 single-turn pair metrics comparable (ADR 0014).
    assertEquals(CacheScope.of(List.of(), null, null, null),
        CacheScope.of(List.of(), null, null, null));
  }

  @Test
  void itIsAHexSha256AndNeverText() {
    String scope = CacheScope.of(PERSONA, null, null, null);
    assertEquals(64, scope.length());
    assertTrue(scope.matches("[0-9a-f]{64}"));
  }

  @Test
  void theEncodingIsPinned() {
    // Every stored entry is keyed on this hash: an encoding change would make
    // the whole cache unreachable. Computed independently of the Java code.
    assertEquals("140ca83b181518eb0bcdf0987f459e4f8be95816baec70ec3a5bbcc01ae37777",
        CacheScope.of(PERSONA, null, null, null));
  }

  @Test
  void eachPartOfTheContextChangesTheScope() {
    String base = CacheScope.of(PERSONA, null, null, null);
    assertNotEquals(base, CacheScope.of(
        List.of(new CacheScope.Turn("system", "Answer in Spanish.")), null, null, null));
    assertNotEquals(base, CacheScope.of(PERSONA, "model-a", null, null));
    assertNotEquals(base, CacheScope.of(PERSONA, null, "user-1", null));
    assertNotEquals(base, CacheScope.of(PERSONA, null, null, List.of("\n")));
    assertNotEquals(base, CacheScope.of(List.of(), null, null, null));
  }

  @Test
  void theRoleIsPartOfATurn() {
    assertNotEquals(
        CacheScope.of(List.of(new CacheScope.Turn("system", "Be brief.")), null, null, null),
        CacheScope.of(List.of(new CacheScope.Turn("user", "Be brief.")), null, null, null));
  }

  @Test
  void theOrderOfTheHistoryMatters() {
    CacheScope.Turn first = new CacheScope.Turn("user", "first");
    CacheScope.Turn second = new CacheScope.Turn("assistant", "second");
    assertNotEquals(CacheScope.of(List.of(first, second), null, null, null),
        CacheScope.of(List.of(second, first), null, null, null));
  }

  @Test
  void noTextCanForgeAFieldBoundary() {
    // Length prefixes: "ab" + "c" is not "a" + "bc".
    assertNotEquals(
        CacheScope.of(List.of(new CacheScope.Turn("user", "ab"),
            new CacheScope.Turn("user", "c")), null, null, null),
        CacheScope.of(List.of(new CacheScope.Turn("user", "a"),
            new CacheScope.Turn("user", "bc")), null, null, null));
    assertNotEquals(CacheScope.of(List.of(), "a", "b", null),
        CacheScope.of(List.of(), "ab", null, null));
  }

  @Test
  void absentIsNotEmpty() {
    assertNotEquals(CacheScope.of(PERSONA, null, null, null),
        CacheScope.of(PERSONA, null, "", null));
    assertNotEquals(CacheScope.of(PERSONA, null, null, null),
        CacheScope.of(PERSONA, null, null, List.of()));
  }

  @Test
  void noNormalisationIsApplied() {
    // Any byte of difference is a different context: the safe direction.
    assertNotEquals(CacheScope.of(PERSONA, null, null, null),
        CacheScope.of(List.of(new CacheScope.Turn("system", "Answer in English. ")),
            null, null, null));
  }
}
