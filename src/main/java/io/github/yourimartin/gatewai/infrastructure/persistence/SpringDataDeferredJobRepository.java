package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataDeferredJobRepository
    extends JpaRepository<DeferredJobEntity, UUID> {

  /**
   * Locks the oldest queued job for the caller's transaction and returns its id.
   *
   * <p>Native rather than a derived query with {@code @Lock}, because
   * {@code SKIP LOCKED} is the whole point and it should be spelled out instead
   * of being requested through a lock-timeout constant a reader has to look up.
   * {@code SKIP LOCKED} is what makes a second worker <em>skip</em> a row another
   * one is claiming rather than queue behind it: no job runs twice, and no worker
   * blocks.
   *
   * <p>The caller then loads the row by id — already locked by this transaction —
   * and flips it to {@code RUNNING} before committing.
   */
  @Query(value = """
      SELECT id FROM deferred_job
       WHERE status = 'QUEUED'
       ORDER BY submitted_at
       FOR UPDATE SKIP LOCKED
       LIMIT 1
      """, nativeQuery = true)
  Optional<UUID> lockNextQueuedId();

  /**
   * Returns every job whose lease has run out to the queue.
   *
   * <p>{@code chosenZone} is cleared with the claim: the job will be re-claimed
   * later, and the greenest zone then is not the one it was picked for. Leaving
   * the old value would make the status API name a zone the job is not going to
   * run in.
   */
  @Modifying
  @Query("""
      update DeferredJobEntity j
         set j.status = :queued, j.claimedBy = null,
             j.leaseExpiresAt = null, j.chosenZone = null
       where j.status = :running and j.leaseExpiresAt < :now
      """)
  int requeueExpiredLeases(@Param("queued") DeferredJobStatus queued,
                           @Param("running") DeferredJobStatus running,
                           @Param("now") Instant now);
}
