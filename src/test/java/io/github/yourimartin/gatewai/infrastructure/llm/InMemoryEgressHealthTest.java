package io.github.yourimartin.gatewai.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryEgressHealthTest {

  private static final ModelDefinition MODEL = new ModelDefinition("k", "p", "m", ModelPricing.NONE,
      EnergyProfile.NOT_ACCOUNTED, ModelTier.LOCAL);
  private static final ModelDefinition OTHER = new ModelDefinition("k2", "p", "m2", ModelPricing.NONE,
      EnergyProfile.NOT_ACCOUNTED, ModelTier.LOCAL);
  private static final long COOLDOWN = Duration.ofSeconds(30).toNanos();

  private final AtomicLong now = new AtomicLong(1_000L);
  private InMemoryEgressHealth health;

  @BeforeEach
  void setUp() {
    FailoverProperties properties = new FailoverProperties();
    properties.setFailureThreshold(3);
    properties.setCooldown(Duration.ofSeconds(30));
    health = new InMemoryEgressHealth(properties, now::get);
  }

  @Test
  void anUnknownModelIsAdmitted() {
    assertThat(health.admit(MODEL)).isTrue();
  }

  @Test
  void staysAdmittedBelowTheThreshold() {
    health.failed(MODEL);
    health.failed(MODEL);

    assertThat(health.admit(MODEL)).isTrue();
  }

  @Test
  void coolsDownAtTheThresholdAndOnlyThatModel() {
    fail(3);

    assertThat(health.admit(MODEL)).isFalse();
    assertThat(health.admit(OTHER)).isTrue();
  }

  @Test
  void aSuccessResetsTheCount() {
    health.failed(MODEL);
    health.failed(MODEL);
    health.succeeded(MODEL);
    health.failed(MODEL);

    assertThat(health.admit(MODEL)).isTrue();
  }

  @Test
  void onceTheCooldownHasElapsedOneRequestProbes() {
    fail(3);
    now.addAndGet(COOLDOWN);

    assertThat(health.admit(MODEL)).as("the probe").isTrue();
    assertThat(health.admit(MODEL)).as("everyone else, while it runs").isFalse();
  }

  @Test
  void aSuccessfulProbeClosesTheRecord() {
    fail(3);
    now.addAndGet(COOLDOWN);
    health.admit(MODEL);

    health.succeeded(MODEL);

    assertThat(health.admit(MODEL)).isTrue();
    assertThat(health.admit(MODEL)).isTrue();
  }

  @Test
  void aFailedProbeStartsAnotherCooldown() {
    fail(3);
    now.addAndGet(COOLDOWN);
    health.admit(MODEL);

    health.failed(MODEL);
    now.addAndGet(COOLDOWN - 1);

    assertThat(health.admit(MODEL)).isFalse();
    now.addAndGet(1);
    assertThat(health.admit(MODEL)).isTrue();
  }

  @Test
  void aProbeThatNeverReportsLeavesTheModelCoolingForOneMoreCooldown() {
    fail(3);
    now.addAndGet(COOLDOWN);
    health.admit(MODEL);

    now.addAndGet(COOLDOWN);

    assertThat(health.admit(MODEL)).isTrue();
  }

  private void fail(int times) {
    for (int i = 0; i < times; i++) {
      health.failed(MODEL);
    }
  }
}
