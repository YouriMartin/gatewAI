package com.example.gatewai.domain.port.out;

import com.example.gatewai.domain.model.RequestLog;

/**
 * Records per-request observability metrics (tokens, latency, cost, carbon,
 * cache hits) for a monitoring backend such as Prometheus. Outbound port so the
 * application stays free of any metrics framework.
 */
public interface MetricsRecorder {

  void record(RequestLog log);
}
