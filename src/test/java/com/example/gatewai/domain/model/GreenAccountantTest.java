package com.example.gatewai.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GreenAccountantTest {

  private static final double DELTA = 1e-9;
  private static final double GRID = 230.0;

  private GreenAccountant accountant;

  @BeforeEach
  void setUp() {
    accountant = new GreenAccountant(new CarbonCalculator());
  }

  private static ModelDefinition premium() {
    return new ModelDefinition(
        "sonnet", "anthropic", "claude-sonnet", 0.015, 0.005,
        ModelTier.CLOUD_PREMIUM);
  }

  private static ModelDefinition entry() {
    return new ModelDefinition(
        "haiku", "anthropic", "claude-haiku", 0.002, 0.002,
        ModelTier.CLOUD_ENTRY);
  }

  @Test
  void computesCostEnergyAndCarbon() {
    // 2000 tokens on entry: cost = 2 * 0.002 = 0.004 ;
    // energy = 2 * 0.002 = 0.004 kWh ; carbon = 0.004 * 230 = 0.92 gCO2
    GreenMetrics metrics = accountant.account(entry(), premium(), 2000, GRID);

    assertEquals(0.004, metrics.costEur(), DELTA);
    assertEquals(0.004, metrics.energyKwh(), DELTA);
    assertEquals(0.92, metrics.gramsCo2(), DELTA);
  }

  @Test
  void avoidedIsPremiumMinusActualEmission() {
    // 1000 tokens: premium carbon = 0.005*230 = 1.15 ;
    // entry carbon = 0.002*230 = 0.46 ; avoided = 0.69
    GreenMetrics metrics = accountant.account(entry(), premium(), 1000, GRID);

    assertEquals(0.69, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void noAvoidanceWhenServedByPremiumItself() {
    GreenMetrics metrics = accountant.account(premium(), premium(), 1000, GRID);

    assertEquals(0.0, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void avoidanceNeverNegativeWhenActualDirtierThanBaseline() {
    // Used model dirtier than the premium baseline -> avoided clamped to 0
    ModelDefinition dirty = new ModelDefinition(
        "dirty", "x", "dirty", 0.0, 0.01, ModelTier.LOCAL);

    GreenMetrics metrics = accountant.account(dirty, premium(), 1000, GRID);

    assertTrue(metrics.gramsCo2Avoided() >= 0.0);
    assertEquals(0.0, metrics.gramsCo2Avoided(), DELTA);
  }

  @Test
  void noBaselineMeansNoAvoidance() {
    GreenMetrics metrics = accountant.account(entry(), null, 1000, GRID);

    assertEquals(0.0, metrics.gramsCo2Avoided(), DELTA);
    assertTrue(metrics.gramsCo2() > 0.0);
  }

  @Test
  void unknownUsedModelReturnsZero() {
    assertSame(GreenMetrics.ZERO, accountant.account(null, premium(), 1000, GRID));
  }

  @Test
  void zeroTokensReturnsZero() {
    assertSame(GreenMetrics.ZERO, accountant.account(entry(), premium(), 0, GRID));
  }
}
