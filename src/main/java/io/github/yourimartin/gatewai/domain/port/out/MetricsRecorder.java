package io.github.yourimartin.gatewai.domain.port.out;

import io.github.yourimartin.gatewai.domain.model.llm.PassThroughStreamOutcome;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;

/**
 * Records per-request observability metrics (tokens, latency, cost, carbon,
 * cache hits) for a monitoring backend such as Prometheus. Outbound port so the
 * application stays free of any metrics framework.
 */
public interface MetricsRecorder {

  void record(RequestLog log);

  /**
   * Counts how a streamed pass-through ended (v4 B.4), including the streams no
   * {@link RequestLog} exists for: a client that disconnects leaves no usage to log.
   */
  void recordPassThroughStream(String model, PassThroughStreamOutcome outcome);
}
