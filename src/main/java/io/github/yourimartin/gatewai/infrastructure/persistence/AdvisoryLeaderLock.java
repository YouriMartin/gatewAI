package io.github.yourimartin.gatewai.infrastructure.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LeaderLock} on {@code pg_try_advisory_xact_lock} (v3 lot B.4).
 *
 * <p>No table, no migration, no lease to expire: PostgreSQL already has exactly
 * this primitive, and lot B's constraint is to use the database that is already
 * there rather than add infrastructure.
 *
 * <p><b>Transaction-scoped, not session-scoped</b>, which is the choice that
 * matters. A session lock has to be released by hand, so a node killed mid-job
 * holds it until its connection is reaped — the failure mode that turns "one node
 * died" into "nothing runs any more". A transaction lock is released by the
 * commit, the rollback, <em>or</em> the connection dying, so a crashed node
 * releases it without anyone knowing it crashed.
 *
 * <p>That scoping is also why {@code work} runs <b>inside</b> this transaction:
 * the lock is held for exactly as long as the job, and the job's own writes join
 * the same transaction and the same connection. A job that wrote through a
 * different {@code DataSource} would run outside the lock's protection — none
 * does, and {@link LeaderTask} is where a new one would have to be declared.
 */
@Component
class AdvisoryLeaderLock implements LeaderLock {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdvisoryLeaderLock.class);

  /**
   * Namespace for every gatewAI advisory lock, so a lock id cannot collide with
   * another application sharing the database. {@code String.hashCode} is
   * specified by the JDK, so this constant is the same in every JVM, forever.
   */
  private static final int NAMESPACE = "gatewai".hashCode();

  private final JdbcTemplate jdbc;

  AdvisoryLeaderLock(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public boolean runIfLeader(LeaderTask task, Runnable work) {
    Boolean acquired = jdbc.queryForObject(
        "SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class,
        NAMESPACE, task.lockId());
    if (!Boolean.TRUE.equals(acquired)) {
      LOG.debug("{} is running on another instance; skipping", task);
      return false;
    }
    work.run();
    return true;
  }
}
