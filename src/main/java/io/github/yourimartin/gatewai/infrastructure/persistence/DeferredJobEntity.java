package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.DeferredJob;
import io.github.yourimartin.gatewai.domain.model.DeferredJobStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A queued, running or finished deferred job (v3 lot B.2).
 *
 * <p>Carries two columns the domain {@link DeferredJob} does not know about —
 * {@code claimedBy} and {@code leaseExpiresAt}. They are how a claim is made
 * recoverable, which is a property of running the queue rather than of the job,
 * so they stay here and out of the domain record.
 */
@Entity
@Table(name = "deferred_job")
class DeferredJobEntity {

  @Id
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private DeferredJobStatus status;

  @Column(name = "client_id")
  private String clientId;

  /** The prompt, in clear text. See {@link DeferredJobJson}. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false)
  private String request;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column
  private String result;

  @Column(name = "chosen_zone", length = 32)
  private String chosenZone;

  @Column(name = "error_message")
  private String errorMessage;

  /** Which node holds the claim — the answer to "where did this job run?". */
  @Column(name = "claimed_by")
  private String claimedBy;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "submitted_at", nullable = false)
  private Instant submittedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  protected DeferredJobEntity() {
    // JPA requires a no-arg constructor
  }

  DeferredJobEntity(DeferredJob job) {
    this.id = job.id();
    this.clientId = job.clientId();
    this.request = DeferredJobJson.requestToJson(job.request());
    this.submittedAt = job.submittedAt();
    apply(job);
  }

  /**
   * Copies the mutable half of a job onto this row, leaving the claim
   * bookkeeping alone — a completion must not erase which node ran the job.
   * The lease is released on a terminal status, because there is nothing left to
   * reclaim.
   */
  final void apply(DeferredJob job) {
    this.status = job.status();
    this.chosenZone = job.chosenZone();
    this.result = DeferredJobJson.responseToJson(job.result());
    this.errorMessage = job.errorMessage();
    this.completedAt = job.completedAt();
    if (job.status() == DeferredJobStatus.COMPLETED
        || job.status() == DeferredJobStatus.FAILED) {
      this.leaseExpiresAt = null;
    }
  }

  /**
   * Takes the job for {@code node} until {@code leaseExpiresAt}, in {@code zone}.
   *
   * <p>The status change goes through the domain's own {@code running(zone)}
   * transition rather than being re-implemented here, so there is one definition
   * of what entering {@code RUNNING} means and the store only adds the
   * bookkeeping that makes it recoverable.
   */
  void claim(String zone, String node, Instant leaseExpiresAt) {
    apply(toDomain().running(zone));
    this.claimedBy = node;
    this.leaseExpiresAt = leaseExpiresAt;
  }

  /**
   * The claim bookkeeping, which {@link #toDomain()} cannot expose because the
   * domain record has nowhere to put it — and which the store's tests have to be
   * able to assert without a database.
   */
  String claimedBy() {
    return claimedBy;
  }

  Instant leaseExpiresAt() {
    return leaseExpiresAt;
  }

  DeferredJob toDomain() {
    return new DeferredJob(
        id,
        DeferredJobJson.requestFromJson(request),
        clientId,
        status,
        DeferredJobJson.responseFromJson(result),
        chosenZone,
        errorMessage,
        submittedAt,
        completedAt);
  }
}
