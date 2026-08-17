package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;
import io.github.yourimartin.gatewai.domain.port.out.DeferredJobStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA adapter for {@link DeferredJobStore} (v3 lot B.2). Replaces the in-memory
 * map: jobs now survive a restart and are visible to every replica, which is what
 * lets a client poll the status through a load balancer and get an answer rather
 * than a 404 from whichever node did not take the submission.
 */
@Component
class JpaDeferredJobStore implements DeferredJobStore {

  private static final Logger LOG =
      LoggerFactory.getLogger(JpaDeferredJobStore.class);

  private final SpringDataDeferredJobRepository repository;
  private final Duration leaseTtl;
  private final String nodeId;

  JpaDeferredJobStore(
      SpringDataDeferredJobRepository repository,
      @Value("${gatewai.dispatch.job-lease-ms:300000}") long leaseMs,
      @Value("${gatewai.instance-id:}") String instanceId) {
    this.repository = repository;
    this.leaseTtl = Duration.ofMillis(leaseMs);
    this.nodeId = instanceId == null || instanceId.isBlank()
        ? localNodeId() : instanceId.trim();
    LOG.info("Deferred jobs claimed as '{}' with a {} lease", nodeId, leaseTtl);
  }

  /**
   * Inserts a new job, or updates the state of one that exists.
   *
   * <p>An update touches only the mutable half — see
   * {@link DeferredJobEntity#apply}. Writing the whole row from the domain record
   * would erase {@code claimed_by}, and with it the answer to which node ran the
   * job, on the very write that finishes it.
   */
  @Override
  @Transactional
  public void save(DeferredJob job) {
    DeferredJobEntity entity = repository.findById(job.id()).orElse(null);
    if (entity == null) {
      repository.save(new DeferredJobEntity(job));
      return;
    }
    entity.apply(job);
    repository.save(entity);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<DeferredJob> find(UUID id) {
    return repository.findById(id).map(DeferredJobEntity::toDomain);
  }

  /**
   * One job per call, deliberately.
   *
   * <p>Claiming a batch would set every job's lease at the moment the batch was
   * taken, so the last job of a slow batch could have its lease expire before it
   * even started and be requeued while still queued behind its siblings. Taking
   * one job at a time makes the lease start when the work does, lets two workers
   * interleave on the same queue instead of splitting it into blocks, and keeps
   * the lease default a statement about <em>one</em> completion.
   */
  @Override
  @Transactional
  public Optional<DeferredJob> claimNextQueued(String zone) {
    return repository.lockNextQueuedId()
        .flatMap(repository::findById)
        .map(entity -> {
          entity.claim(zone, nodeId, Instant.now().plus(leaseTtl));
          return repository.saveAndFlush(entity).toDomain();
        });
  }

  @Override
  @Transactional
  public int requeueExpiredLeases() {
    return repository.requeueExpiredLeases(
        DeferredJobStatus.QUEUED, DeferredJobStatus.RUNNING, Instant.now());
  }

  /**
   * {@code host:pid}, which is enough to tell two replicas apart in a table and
   * in a log. Override with {@code gatewai.instance-id} when the deployment
   * already has a name for the node (a pod name, say).
   */
  private static String localNodeId() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      host = "unknown-host";
    }
    return host + ":" + ProcessHandle.current().pid();
  }
}
