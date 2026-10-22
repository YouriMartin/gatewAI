package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.port.out.EgressHealth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Health records per registry entry, <b>per node and in memory</b> (ADR 0017,
 * v4 B.5). Each node relearns an outage in a few failed requests; sharing the
 * records would put a database write on every failed upstream call.
 *
 * <p>A record is closed, cooling, or probing. Cooling starts after
 * {@code gatewai.failover.failure-threshold} consecutive transient failures and
 * lasts {@code gatewai.failover.cooldown}. Once it has elapsed, the first caller
 * to ask is admitted as the probe, and the deadline moves one cooldown ahead so
 * that the others keep skipping the entry while the probe runs. A probe that
 * never reports (a request that ended before its attempt) simply leaves the
 * entry cooling for one more cooldown.
 */
@Component
class InMemoryEgressHealth implements EgressHealth {

  private static final Logger LOG = LoggerFactory.getLogger(InMemoryEgressHealth.class);

  private final Map<String, Record> records = new ConcurrentHashMap<>();
  private final int failureThreshold;
  private final long cooldownNanos;
  private final LongSupplier nanoClock;

  @Autowired
  InMemoryEgressHealth(FailoverProperties properties) {
    this(properties, System::nanoTime);
  }

  InMemoryEgressHealth(FailoverProperties properties, LongSupplier nanoClock) {
    this.failureThreshold = Math.max(1, properties.getFailureThreshold());
    this.cooldownNanos = properties.getCooldown().toNanos();
    this.nanoClock = nanoClock;
  }

  @Override
  public boolean admit(ModelDefinition model) {
    Record record = records.get(model.modelId());
    return record == null || record.admit(nanoClock.getAsLong(), cooldownNanos);
  }

  @Override
  public void succeeded(ModelDefinition model) {
    Record record = records.get(model.modelId());
    if (record != null && record.close()) {
      LOG.info("Model '{}' answered again: it is no longer skipped", model.modelId());
    }
  }

  @Override
  public void failed(ModelDefinition model) {
    Record record = records.computeIfAbsent(model.modelId(), id -> new Record());
    if (record.fail(nanoClock.getAsLong(), failureThreshold, cooldownNanos)) {
      LOG.warn("Model '{}' failed {} times in a row: skipped for {} ms, then probed",
          model.modelId(), failureThreshold, cooldownNanos / 1_000_000);
    }
  }

  /** One entry's state; every transition holds its lock, and none of them blocks. */
  private static final class Record {

    private int consecutiveFailures;
    /** {@code nanoTime} before which the entry is skipped; meaningless while closed. */
    private long skipUntil;
    private boolean cooling;

    synchronized boolean admit(long now, long cooldownNanos) {
      if (!cooling) {
        return true;
      }
      if (now - skipUntil < 0) {
        return false;
      }
      // The probe: this caller goes, the others wait for its outcome.
      skipUntil = now + cooldownNanos;
      return true;
    }

    /** @return true when this closed a cooling record */
    synchronized boolean close() {
      boolean wasCooling = cooling;
      consecutiveFailures = 0;
      cooling = false;
      return wasCooling;
    }

    /** @return true when this failure started a cooldown from a closed record */
    synchronized boolean fail(long now, int threshold, long cooldownNanos) {
      consecutiveFailures++;
      if (consecutiveFailures < threshold) {
        return false;
      }
      boolean opened = !cooling;
      cooling = true;
      skipUntil = now + cooldownNanos;
      return opened;
    }
  }
}
