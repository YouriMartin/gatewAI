package io.github.yourimartin.gatewai.domain.model.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;

/**
 * The models one request may be sent to, in the order it tries them (ADR 0017,
 * v4 B.5): its first target, then the other entries of that target's tier in
 * failover order.
 *
 * <p>An entry the health records turn away (a cooldown) is skipped, and tried
 * only when nothing else could be: the cooldown reorders a chain, it never
 * refuses a request the provider may already be able to serve. Admission is
 * asked lazily, one candidate at a time, because admitting an entry whose
 * cooldown has elapsed claims its probe — a candidate the request never reaches
 * must not take it.
 *
 * <p>Not thread-safe: one sequence belongs to one request, and its attempts are
 * sequential.
 */
public final class FailoverSequence {

  private final Deque<ModelDefinition> pending;
  private final Predicate<ModelDefinition> admit;
  private final List<ModelDefinition> skipped = new ArrayList<>();
  private boolean attempted;
  private boolean forced;

  private FailoverSequence(List<ModelDefinition> candidates, Predicate<ModelDefinition> admit) {
    this.pending = new ArrayDeque<>(candidates);
    this.admit = admit;
  }

  /**
   * A request that may fail over.
   *
   * @param first the model the request was sent to first
   * @param tier  every entry of {@code first}'s tier, in failover order
   * @param admit whether an entry may be tried now; asked once per candidate
   */
  public static FailoverSequence of(ModelDefinition first, List<ModelDefinition> tier,
                                    Predicate<ModelDefinition> admit) {
    Set<ModelDefinition> ordered = new LinkedHashSet<>();
    ordered.add(first);
    tier.stream().filter(model -> model.tier() == first.tier()).forEach(ordered::add);
    return new FailoverSequence(List.copyOf(ordered), admit);
  }

  /** A request that must be served by {@code only}, or not at all: a pin. */
  public static FailoverSequence single(ModelDefinition only) {
    return new FailoverSequence(List.of(only), model -> true);
  }

  /**
   * The next model to try, or empty when the chain is exhausted. Cooling
   * entries come back, in order, only when no entry at all could be tried.
   */
  public Optional<ModelDefinition> next() {
    while (!pending.isEmpty()) {
      ModelDefinition candidate = pending.poll();
      if (forced || admit.test(candidate)) {
        attempted = true;
        return Optional.of(candidate);
      }
      skipped.add(candidate);
    }
    if (!attempted && !skipped.isEmpty()) {
      // Every candidate is cooling down: try them all, as declared.
      forced = true;
      pending.addAll(skipped);
      skipped.clear();
      return next();
    }
    return Optional.empty();
  }
}
