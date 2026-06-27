package com.example.gatewai.domain.port.in;

import java.util.Map;

/** Dispatches queued jobs to the greenest available zone. Driven by a worker. */
public interface DispatchDeferredJobsUseCase {

  /**
   * Runs every queued job now, accounting emissions at the greenest of the
   * supplied zones.
   *
   * @param zoneIntensities zone id → current grid carbon intensity (gCO2/kWh);
   *                        empty to dispatch without a zone preference
   */
  void dispatchPending(Map<String, Double> zoneIntensities);
}
