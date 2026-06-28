package com.example.gatewai.adapter.in.mcp;

/**
 * MCP-facing result of a carbon-intensity lookup.
 *
 * @param zone           grid zone the figure applies to ({@code default} when
 *                       the configured zone was used)
 * @param gramsCo2PerKwh current grid carbon intensity, in gCO2-eq per kWh
 */
record CarbonIntensityToolResult(String zone, double gramsCo2PerKwh) {
}
