package io.github.yourimartin.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The prefill/decode split and the invariants that keep a label honest (v3 lot C.4). */
class EnergyProfileTest {

  private static final double DELTA = 1e-12;

  /** The shipped Claude-Opus-class example: see green-accounting.md for sources. */
  private static EnergyProfile opusClass() {
    return new EnergyProfile(0.000393, 0.0161, 0.0, EnergySource.MODELLED, false);
  }

  @Test
  void energyIsLinearInBothPhasesPlusAFixedTerm() {
    EnergyProfile profile =
        new EnergyProfile(0.001, 0.01, 0.0005, EnergySource.MODELLED, false);

    // 2000 prompt x 0.001 + 500 completion x 0.01 + 0.0005
    assertEquals(0.002 + 0.005 + 0.0005, profile.kwh(2000, 500), DELTA);
  }

  @Test
  void aLongPromptAndItsMirrorImageDifferWhichIsTheWholePoint() {
    EnergyProfile profile = opusClass();

    double longPromptShortAnswer = profile.kwh(10_000, 50);
    double shortPromptLongAnswer = profile.kwh(50, 10_000);

    assertTrue(shortPromptLongAnswer > longPromptShortAnswer);
    // Decode dominates by design: generating 10k tokens costs ~40x reading them.
    // 10 000 x 0.000393/1k + 50 x 0.0161/1k = 0.00393 + 0.000805
    assertEquals(4.735e-3, longPromptShortAnswer, 1e-12);
    // 50 x 0.000393/1k + 10 000 x 0.0161/1k = 0.00001965 + 0.161
    assertEquals(0.16101965, shortPromptLongAnswer, 1e-12);
    assertEquals(34.0, shortPromptLongAnswer / longPromptShortAnswer, 0.05);
  }

  @Test
  void negativeTokenCountsCountAsZeroRatherThanSubtractingEnergy() {
    EnergyProfile profile = opusClass();

    assertEquals(profile.fixedKwhPerRequest(), profile.kwh(-10, -10), DELTA);
  }

  @Test
  void anAllZeroProfileWithNoDeclaredSourceIsExcludedFromScope() {
    EnergyProfile derived = new EnergyProfile(0.0, 0.0, 0.0, null, false);

    assertEquals(EnergySource.NOT_ACCOUNTED, derived.source());
    assertFalse(derived.accounted());
  }

  @Test
  void aCoefficientWithNoDeclaredSourceIsAModelledEstimate() {
    EnergyProfile derived = new EnergyProfile(0.0, 0.01, 0.0, null, false);

    assertEquals(EnergySource.MODELLED, derived.source());
    assertTrue(derived.accounted());
  }

  @Test
  void anUnaccountedProfileCannotCarryACoefficient() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new EnergyProfile(0.0, 0.002, 0.0, EnergySource.NOT_ACCOUNTED, false));

    assertTrue(error.getMessage().contains("not-accounted"), error.getMessage());
  }

  @Test
  void negativeCoefficientsAreRefused() {
    assertThrows(IllegalArgumentException.class,
        () -> new EnergyProfile(-0.1, 0.0, 0.0, null, false));
    assertThrows(IllegalArgumentException.class,
        () -> new EnergyProfile(0.0, -0.1, 0.0, null, false));
    assertThrows(IllegalArgumentException.class,
        () -> new EnergyProfile(0.0, 0.0, -0.1, null, false));
    assertThrows(IllegalArgumentException.class,
        () -> new EnergyProfile(Double.NaN, 0.0, 0.0, null, false));
  }

  @Test
  void theExcludedProfileIsZeroWhateverTheUsage() {
    assertEquals(0.0, EnergyProfile.NOT_ACCOUNTED.kwh(1_000_000, 1_000_000), DELTA);
    assertFalse(EnergyProfile.NOT_ACCOUNTED.accounted());
  }
}
