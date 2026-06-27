package com.example.gatewai.domain.model;

import java.time.Instant;
import java.util.UUID;

public record ApiClient(
    UUID id,
    String name,
    String apiKeyHash,
    boolean enabled,
    Instant createdAt
) {}
