package io.github.yourimartin.gatewai.infrastructure.persistence;

/**
 * The periodic jobs that must run on <b>one</b> node at a time (v3 lot B.4).
 *
 * <p>Each carries its own lock id rather than hashing its name: two jobs sharing
 * a key would silently serialize against each other, and a hash collision is
 * exactly the kind of bug that only appears once a third job is added. Declaring
 * the id makes adding a job a decision someone took, which is the same discipline
 * ArchUnit applies to adapter packages.
 *
 * <p><b>Ids are permanent.</b> Reusing one for a different job would let a new
 * node's job block an old node's during a rolling upgrade.
 *
 * <p>Not everything scheduled belongs here. A job qualifies only if running it
 * twice is wrong <em>and</em> it has no claim of its own:
 * <ul>
 *   <li>the routing-config poll is a read and <b>must</b> run everywhere (B.1);</li>
 *   <li>the dispatch worker coordinates through {@code FOR UPDATE SKIP LOCKED} on
 *       the queue itself, and gating it would elect one dispatcher and idle the
 *       rest (B.2);</li>
 *   <li>the conformal snapshot refresh is a per-node read-through cache.</li>
 * </ul>
 */
enum LeaderTask {

  /** Dropping decision rows past their retention window. */
  DECISION_PURGE(1),

  /** Creating the bootstrap admin client at startup. */
  ADMIN_SEED(2);

  private final int lockId;

  LeaderTask(int lockId) {
    this.lockId = lockId;
  }

  int lockId() {
    return lockId;
  }
}
