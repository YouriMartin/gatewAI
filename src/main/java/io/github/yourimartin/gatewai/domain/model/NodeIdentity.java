package io.github.yourimartin.gatewai.domain.model;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The name this instance answers to when there is more than one (v3 lot B.5).
 *
 * <p>Lives in the domain for a reason that is not architectural tidiness: two
 * adapters need the <b>same</b> string. The deferred-job store writes it to
 * {@code claimed_by} ("which node ran this?") and the metrics adapter publishes
 * it as the {@code instance} tag ("which node is this series from?"). If those
 * two ever disagreed, a job traced to one name and a graph labelled with another
 * would describe the same node and nobody could tell.
 */
public final class NodeIdentity {

  private NodeIdentity() {
  }

  /**
   * {@code configured} when an operator set one, {@code host:pid} otherwise.
   *
   * <p>The fallback is enough to tell replicas apart without any configuration,
   * which matters because the alternative — every node calling itself
   * "gatewai" — silently merges their series and their claims. Deployments that
   * already have a name for the node (a pod name, say) should set it.
   */
  public static String resolve(String configured) {
    if (configured != null && !configured.isBlank()) {
      return configured.trim();
    }
    return hostname() + ":" + ProcessHandle.current().pid();
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "unknown-host";
    }
  }
}
