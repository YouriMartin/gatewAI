package io.github.yourimartin.gatewai.infrastructure.persistence;

/**
 * Runs a job on one node at a time (v3 lot B.4).
 *
 * <p>Deliberately <b>not</b> "elect a leader". There is no leader here, no term,
 * no heartbeat and nothing to fail over: each tick asks for the lock, does the
 * work if it gets it, and forgets. A node that dies holds nothing, so the next
 * tick on any other node simply wins — which is the whole recovery story, and it
 * needs no operator.
 */
interface LeaderLock {

  /**
   * Runs {@code work} if this node can take {@code task}'s lock, and does nothing
   * if another node holds it.
   *
   * <p>Non-blocking on purpose: a node that has to wait for the previous tick to
   * finish would queue ticks behind a slow job instead of skipping one.
   *
   * @return {@code true} if the work ran here, {@code false} if it was skipped
   */
  boolean runIfLeader(LeaderTask task, Runnable work);
}
