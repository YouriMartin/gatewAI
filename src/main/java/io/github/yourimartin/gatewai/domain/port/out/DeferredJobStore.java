package io.github.yourimartin.gatewai.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;

/** Stores deferred jobs and exposes the queued ones to the dispatch worker. */
public interface DeferredJobStore {

  void save(DeferredJob job);

  Optional<DeferredJob> find(UUID id);

  List<DeferredJob> findQueued();
}
