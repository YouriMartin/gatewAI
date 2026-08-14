package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.yourimartin.gatewai.domain.model.CacheDecision;
import io.github.yourimartin.gatewai.domain.model.RoutingDecision;
import io.github.yourimartin.gatewai.domain.model.TracedDecision;
import io.github.yourimartin.gatewai.domain.port.out.DecisionHistory;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the decision trace back out of PostgreSQL (v2 batch 9).
 *
 * <p>Deliberately separate from {@code AsyncDecisionRecorder}: that one must
 * never block and never throw, this one is a synchronous admin query whose
 * failure is worth surfacing. Sharing a class would have meant sharing a
 * contract that suits neither.
 *
 * <p>The history is <b>merged across both tables</b>. A cache hit short-circuits
 * the chain and never reaches the router, so a "recent decisions" list built on
 * routing rows alone would omit precisely the requests the cache answered — the
 * ones where a wrong decision costs a wrong answer rather than a few cents.
 */
@Component
class JpaDecisionHistory implements DecisionHistory {

  private final SpringDataRoutingDecisionRepository routingRepository;
  private final SpringDataCacheDecisionRepository cacheRepository;

  JpaDecisionHistory(SpringDataRoutingDecisionRepository routingRepository,
                     SpringDataCacheDecisionRepository cacheRepository) {
    this.routingRepository = routingRepository;
    this.cacheRepository = cacheRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TracedDecision> byCorrelationId(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      return Optional.empty();
    }
    RoutingDecision routing = routingRepository
        .findFirstByCorrelationIdOrderByCreatedAtDesc(correlationId)
        .map(RoutingDecisionEntity::toDomain).orElse(null);
    CacheDecision cache = cacheRepository
        .findFirstByCorrelationIdOrderByCreatedAtDesc(correlationId)
        .map(CacheDecisionEntity::toDomain).orElse(null);

    if (routing == null && cache == null) {
      return Optional.empty();
    }
    return Optional.of(TracedDecision.of(correlationId, cache, routing));
  }

  /**
   * Newest first, in four bounded queries: the top {@code limit} of each table,
   * then the counterparts of whatever came back half-joined. The second pair
   * matters — a request whose cache decision is old enough to have fallen out of
   * the top {@code limit} would otherwise be rendered as if the cache had never
   * looked at it.
   */
  @Override
  @Transactional(readOnly = true)
  public List<TracedDecision> recent(int limit) {
    if (limit <= 0) {
      return List.of();
    }
    PageRequest page = PageRequest.of(0, limit);
    Map<String, Parts> merged = new LinkedHashMap<>();

    for (RoutingDecisionEntity entity
        : routingRepository.findAllByOrderByCreatedAtDesc(page)) {
      RoutingDecision routing = entity.toDomain();
      partsFor(merged, routing.correlationId(), "routing:" + routing.id())
          .routingIfAbsent(routing);
    }
    for (CacheDecisionEntity entity
        : cacheRepository.findAllByOrderByCreatedAtDesc(page)) {
      CacheDecision cache = entity.toDomain();
      partsFor(merged, cache.correlationId(), "cache:" + cache.id())
          .cacheIfAbsent(cache);
    }

    fillMissingHalves(merged);

    return merged.entrySet().stream()
        .filter(entry -> entry.getValue().isPresent())
        .map(entry -> entry.getValue().toDecision(entry.getKey()))
        .sorted(Comparator.comparing(TracedDecision::at).reversed())
        .limit(limit)
        .toList();
  }

  /** Fetches the halves the two top-N windows did not line up on. */
  private void fillMissingHalves(Map<String, Parts> merged) {
    List<String> needCache = idsWhere(merged, parts -> parts.cache == null);
    if (!needCache.isEmpty()) {
      for (CacheDecisionEntity entity
          : cacheRepository.findByCorrelationIdInOrderByCreatedAtDesc(needCache)) {
        CacheDecision cache = entity.toDomain();
        merged.get(cache.correlationId()).cacheIfAbsent(cache);
      }
    }

    List<String> needRouting = idsWhere(merged, parts -> parts.routing == null);
    if (!needRouting.isEmpty()) {
      for (RoutingDecisionEntity entity
          : routingRepository.findByCorrelationIdInOrderByCreatedAtDesc(needRouting)) {
        RoutingDecision routing = entity.toDomain();
        merged.get(routing.correlationId()).routingIfAbsent(routing);
      }
    }
  }

  private static List<String> idsWhere(Map<String, Parts> merged,
                                       Predicate<Parts> missing) {
    List<String> ids = new ArrayList<>();
    for (Map.Entry<String, Parts> entry : merged.entrySet()) {
      if (!entry.getValue().synthetic && missing.test(entry.getValue())) {
        ids.add(entry.getKey());
      }
    }
    return ids;
  }

  /**
   * The entry a row belongs to. A row without a correlation id still happened,
   * and dropping it would make the history quietly incomplete: it gets a
   * synthetic key built from its own row id, so it stands alone instead of
   * merging with every other id-less row.
   */
  private static Parts partsFor(Map<String, Parts> merged, String correlationId,
                                String syntheticKey) {
    if (correlationId != null && !correlationId.isBlank()) {
      return merged.computeIfAbsent(correlationId, key -> new Parts());
    }
    return merged.computeIfAbsent(syntheticKey, key -> Parts.synthetic());
  }

  /** The two halves of one request while they are being assembled. */
  private static final class Parts {

    private CacheDecision cache;
    private RoutingDecision routing;
    private boolean synthetic;

    static Parts synthetic() {
      Parts parts = new Parts();
      parts.synthetic = true;
      return parts;
    }

    /** Keeps the newest, since every query already sorts descending. */
    void cacheIfAbsent(CacheDecision candidate) {
      if (cache == null) {
        cache = candidate;
      }
    }

    void routingIfAbsent(RoutingDecision candidate) {
      if (routing == null) {
        routing = candidate;
      }
    }

    boolean isPresent() {
      return cache != null || routing != null;
    }

    TracedDecision toDecision(String key) {
      String correlationId = synthetic ? null : key;
      return TracedDecision.of(correlationId, cache, routing);
    }
  }
}
