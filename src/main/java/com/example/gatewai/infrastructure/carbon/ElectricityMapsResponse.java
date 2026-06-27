package com.example.gatewai.infrastructure.carbon;

/**
 * Subset of the ElectricityMaps {@code /carbon-intensity/latest} payload we
 * care about. Unknown fields are ignored by the default Jackson configuration.
 *
 * @param zone            grid zone the value applies to (e.g. {@code FR})
 * @param carbonIntensity live grid carbon intensity, in gCO2-eq per kWh
 */
record ElectricityMapsResponse(String zone, double carbonIntensity) {}
