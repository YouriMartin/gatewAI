package com.example.gatewai.domain.model;

/**
 * A model the gateway can route to, with its cost and energy coefficients.
 *
 * @param key             registry key (stable identifier in configuration)
 * @param provider        provider name (e.g. {@code anthropic}, {@code ollama})
 * @param modelId         provider-specific model id sent on the wire
 * @param costPer1kTokens monetary cost per 1000 tokens, in the billing currency
 * @param energyIntensity estimated electrical energy per 1000 tokens, in kWh
 *                        (energy only — carbon is energy × grid intensity)
 * @param tier            complexity tier used by the router
 */
public record ModelDefinition(
    String key,
    String provider,
    String modelId,
    double costPer1kTokens,
    double energyIntensity,
    ModelTier tier
) {}
