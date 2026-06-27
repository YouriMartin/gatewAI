package com.example.gatewai.domain.model;

public record ModelDefinition(
    String key,
    String provider,
    String modelId,
    double costPer1kTokens,
    double energyIntensity,
    ModelTier tier
) {}
