package io.github.yourimartin.gatewai.adapter.in.web;

/**
 * What to explain: a past request, or a prompt (v2 batch 9).
 *
 * <p>Exactly one of the two. They answer different questions — "what happened to
 * this request" and "what would happen to this text" — and a body carrying both
 * is a caller who has not decided which one they are asking, so it is a 400
 * rather than a silent preference.
 *
 * <p>The plan called the first field {@code decisionId}. It is
 * {@code correlationId} here because that is the id that exists outside the
 * database: a decision row's surrogate id is never exposed, while the
 * correlation id is on the response header, in the logs and on the carbon
 * record — the whole reason it was introduced in batch 0.3.
 *
 * @param correlationId a past request's id
 * @param prompt        text to analyse against the rules in force now
 */
record ExplainRequest(String correlationId, String prompt) {

  boolean hasCorrelationId() {
    return correlationId != null && !correlationId.isBlank();
  }

  boolean hasPrompt() {
    return prompt != null && !prompt.isBlank();
  }
}
