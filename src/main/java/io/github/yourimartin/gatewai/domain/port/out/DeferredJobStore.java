package io.github.yourimartin.gatewai.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;

/**
 * Stores deferred jobs and hands them out, one at a time, to whichever dispatch
 * worker asks first.
 *
 * <p>There is deliberately no {@code findQueued()} any more (v3 lot B.2). Reading
 * the queue and then writing each job's new status is two operations, and with
 * two workers polling the same table it is a race: both read the same job, both
 * run it. {@link #claimNextQueued(String)} is the same intent expressed as one
 * atomic operation, which is what makes a second worker safe to add.
 */
public interface DeferredJobStore {

  void save(DeferredJob job);

  Optional<DeferredJob> find(UUID id);

  /**
   * Takes ownership of the oldest queued job, marking it {@code RUNNING} in
   * {@code zone} and leasing it to the calling node, or returns empty when the
   * queue holds nothing claimable.
   *
   * <p>Must be atomic: two workers calling this concurrently never receive the
   * same job, and neither waits on the other.
   *
   * @param zone the grid zone the job will be executed at, or {@code null}
   */
  Optional<DeferredJob> claimNextQueued(String zone);

  /**
   * Returns jobs whose lease has expired to the queue, and how many that was.
   *
   * <p>A claim is a lease rather than a promise, because the node holding it can
   * die mid-job and then nothing releases the row. Called on every dispatch tick,
   * so a job stranded by a node that never comes back is recovered by whichever
   * node is still alive — which requeue-on-startup would not do.
   */
  int requeueExpiredLeases();
}
