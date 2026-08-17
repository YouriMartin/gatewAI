package io.github.yourimartin.gatewai.infrastructure.metrics;

import io.github.yourimartin.gatewai.domain.model.NodeIdentity;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tags every series with the instance that produced it (v3 lot B.5).
 *
 * <p>{@code application=gatewai} already came from
 * {@code management.metrics.tags.application}, and with one node that was enough.
 * With two it is actively misleading: the same series name arrives twice, and a
 * panel that does not aggregate silently shows whichever node Prometheus
 * scraped, while one that sums shows a total nobody can attribute.
 *
 * <p>The value is {@link NodeIdentity}'s, the same string the deferred-job store
 * writes to {@code claimed_by}. A dashboard and a job row therefore name a node
 * the same way, which is the difference between "instance-2 is slow" being a
 * lead and being a coincidence.
 *
 * <p>Read {@code gatewai_routing_config_changes_total} with this in mind: every
 * node counts the edit it observed, so one edit reads as N summed across
 * replicas. The provisioned drift panel takes the {@code max} across instances
 * for exactly that reason.
 */
@Configuration
class InstanceTagConfiguration {

  @Bean
  MeterRegistryCustomizer<MeterRegistry> instanceTag(
      @Value("${gatewai.instance-id:}") String instanceId) {
    String node = NodeIdentity.resolve(instanceId);
    return registry -> registry.config().commonTags("instance", node);
  }
}
