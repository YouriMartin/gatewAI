package io.github.yourimartin.gatewai.domain.model.llm;

/**
 * A successful upstream answer on the pass-through path (ADR 0016, v4 B.3),
 * relayed to the client as it came.
 *
 * @param status the upstream HTTP status, always 2xx: an error is an
 *               {@link UpstreamFailureException}, never a response
 * @param body   the upstream body, as JSON text, opaque to the domain
 * @param model  the registry model id the request was sent to
 * @param usage  the token counts the upstream reported, or {@code null} when it
 *               reported none. Null is not zero: it means "not accounted"
 */
public record PassThroughResponse(int status, String body, String model, TokenUsage usage) {
}
