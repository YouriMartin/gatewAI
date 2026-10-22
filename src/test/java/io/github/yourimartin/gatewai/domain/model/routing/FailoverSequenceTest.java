package io.github.yourimartin.gatewai.domain.model.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;

import org.junit.jupiter.api.Test;

class FailoverSequenceTest {

  private static final ModelDefinition A = model("a", ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition B = model("b", ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition C = model("c", ModelTier.CLOUD_PREMIUM);
  private static final ModelDefinition LOCAL = model("local", ModelTier.LOCAL);

  @Test
  void startsWithTheFirstTargetThenFollowsTheTierOrder() {
    FailoverSequence sequence = FailoverSequence.of(B, List.of(A, B, C), m -> true);

    assertThat(drain(sequence)).containsExactly(B, A, C);
  }

  @Test
  void neverLeavesTheFirstTargetsTier() {
    FailoverSequence sequence = FailoverSequence.of(A, List.of(A, LOCAL, B), m -> true);

    assertThat(drain(sequence)).containsExactly(A, B);
  }

  @Test
  void skipsACoolingEntry() {
    FailoverSequence sequence = FailoverSequence.of(A, List.of(A, B, C), m -> m != B);

    assertThat(drain(sequence)).containsExactly(A, C);
  }

  @Test
  void triesEveryEntryAsDeclaredWhenAllAreCooling() {
    FailoverSequence sequence = FailoverSequence.of(A, List.of(A, B, C), m -> false);

    assertThat(drain(sequence)).containsExactly(A, B, C);
  }

  @Test
  void aCoolingEntryIsNotTriedOnceAnotherOneWas() {
    // B could be tried and failed: the cooling ones are not a second chance.
    FailoverSequence sequence = FailoverSequence.of(A, List.of(A, B), m -> m == B);

    assertThat(drain(sequence)).containsExactly(B);
  }

  @Test
  void asksAdmissionOnlyForTheCandidatesItReaches() {
    // Admitting an entry whose cooldown has elapsed claims its probe.
    List<ModelDefinition> asked = new ArrayList<>();
    Predicate<ModelDefinition> admit = m -> asked.add(m);
    FailoverSequence sequence = FailoverSequence.of(A, List.of(A, B, C), admit);

    assertThat(sequence.next()).contains(A);
    assertThat(asked).containsExactly(A);
  }

  @Test
  void aSingleTargetIsTriedEvenWhileCooling() {
    FailoverSequence sequence = FailoverSequence.single(A);

    assertThat(drain(sequence)).containsExactly(A);
  }

  private static List<ModelDefinition> drain(FailoverSequence sequence) {
    List<ModelDefinition> tried = new ArrayList<>();
    for (Optional<ModelDefinition> next = sequence.next(); next.isPresent();
         next = sequence.next()) {
      tried.add(next.get());
      assertThat(Set.copyOf(tried)).hasSize(tried.size());
    }
    return tried;
  }

  private static ModelDefinition model(String id, ModelTier tier) {
    return new ModelDefinition(id, "provider", id, ModelPricing.NONE, EnergyProfile.NOT_ACCOUNTED, tier);
  }
}
