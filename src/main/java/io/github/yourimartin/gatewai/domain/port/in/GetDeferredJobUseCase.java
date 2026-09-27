package io.github.yourimartin.gatewai.domain.port.in;

import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.dispatch.DeferredJob;

/** Looks up a deferred job (status and result). */
public interface GetDeferredJobUseCase {

  Optional<DeferredJob> find(UUID id);
}
