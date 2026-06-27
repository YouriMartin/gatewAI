package com.example.gatewai.adapter.in.web;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Status payload for a carbon-aware deferred job.
 *
 * @param id         job identifier
 * @param status     lifecycle status (queued/running/completed/failed)
 * @param chosenZone grid zone selected at dispatch, or {@code null}
 * @param result     the OpenAI-shaped response once completed, or {@code null}
 * @param error      failure reason when failed, or {@code null}
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeferredJobResponse(
    String id,
    String status,
    String chosenZone,
    ChatCompletionResponse result,
    String error
) {}
