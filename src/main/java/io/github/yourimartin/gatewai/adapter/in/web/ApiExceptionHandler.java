package io.github.yourimartin.gatewai.adapter.in.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/**
 * Translates failures on the OpenAI chat ingress into the OpenAI error envelope
 * ({@link ApiError}) with a matching HTTP status, so client SDKs surface a
 * usable message instead of a Spring whitelabel body. Scoped to the synchronous
 * {@code /v1/chat/completions} controller; streaming failures are reported
 * inline on the SSE stream and do not pass through here.
 *
 * <p>Upstream provider details are logged server-side but not echoed to the
 * caller, to avoid leaking internal configuration.
 */
@RestControllerAdvice(assignableTypes = ChatCompletionController.class)
class ApiExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private static final String INVALID_REQUEST = "invalid_request_error";
  private static final String API_ERROR = "api_error";

  /** Malformed / unparseable JSON body. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    LOG.warn("Malformed request body on /v1/chat/completions: {}", e.getMessage());
    return build(HttpStatus.BAD_REQUEST,
        "Malformed JSON request body.", INVALID_REQUEST, null);
  }

  /** Semantically invalid request (e.g. missing {@code messages}). */
  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ApiError> handleInvalid(IllegalArgumentException e) {
    LOG.warn("Invalid request on /v1/chat/completions: {}", e.getMessage());
    return build(HttpStatus.BAD_REQUEST, e.getMessage(), INVALID_REQUEST, null);
  }

  /** Provider unavailable / rate-limited after retries (retryable upstream). */
  @ExceptionHandler(TransientAiException.class)
  ResponseEntity<ApiError> handleTransient(TransientAiException e) {
    LOG.error("Upstream model provider temporarily unavailable", e);
    return build(HttpStatus.SERVICE_UNAVAILABLE,
        "The upstream model provider is temporarily unavailable. Please retry.",
        API_ERROR, null);
  }

  /** Provider rejected the request (e.g. auth, unknown model, bad input). */
  @ExceptionHandler(NonTransientAiException.class)
  ResponseEntity<ApiError> handleNonTransient(NonTransientAiException e) {
    LOG.error("Upstream model provider rejected the request", e);
    return build(HttpStatus.BAD_GATEWAY,
        "The upstream model provider rejected the request.", API_ERROR, null);
  }

  /** Raw HTTP/transport failure not wrapped by Spring AI (e.g. unreachable). */
  @ExceptionHandler(RestClientException.class)
  ResponseEntity<ApiError> handleRestClient(RestClientException e) {
    LOG.error("Upstream model provider is unreachable", e);
    return build(HttpStatus.BAD_GATEWAY,
        "The upstream model provider is unreachable.", API_ERROR, null);
  }

  /** Anything else: log the detail, return a clean generic error. */
  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception e) {
    LOG.error("Unhandled error on /v1/chat/completions", e);
    return build(HttpStatus.INTERNAL_SERVER_ERROR,
        "An internal error occurred while processing the request.", API_ERROR, null);
  }

  private static ResponseEntity<ApiError> build(HttpStatus status, String message,
                                                String type, String code) {
    return ResponseEntity.status(status).body(ApiError.of(message, type, code));
  }
}
