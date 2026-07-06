package io.github.yourimartin.gatewai.adapter.in.web;

/**
 * OpenAI-compatible error envelope: {@code {"error": {message, type, param, code}}}.
 * Client SDKs (OpenAI, LangChain, …) parse this exact shape, so returning it on
 * failures keeps gatewAI a drop-in proxy on the unhappy path too.
 */
record ApiError(Body error) {

  /** The nested error object. {@code param}/{@code code} may be {@code null}. */
  record Body(String message, String type, String param, String code) {
  }

  static ApiError of(String message, String type, String code) {
    return new ApiError(new Body(message, type, null, code));
  }
}
