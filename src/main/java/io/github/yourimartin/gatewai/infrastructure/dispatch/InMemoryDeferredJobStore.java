package io.github.yourimartin.gatewai.infrastructure.dispatch;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;
import io.github.yourimartin.gatewai.domain.port.out.DeferredJobStore;

import org.springframework.stereotype.Component;

/**
 * In-memory {@link DeferredJobStore}. Adequate for a single-node MVP; a durable
 * (PostgreSQL) store would replace it for multi-node or crash-safe deferral.
 */
@Component
class InMemoryDeferredJobStore implements DeferredJobStore {

  private final Map<UUID, DeferredJob> jobs = new ConcurrentHashMap<>();

  @Override
  public void save(DeferredJob job) {
    jobs.put(job.id(), job);
  }

  @Override
  public Optional<DeferredJob> find(UUID id) {
    return Optional.ofNullable(jobs.get(id));
  }

  @Override
  public List<DeferredJob> findQueued() {
    return jobs.values().stream()
        .filter(job -> job.status() == DeferredJobStatus.QUEUED)
        .toList();
  }
}
