package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.List;

import io.github.yourimartin.gatewai.domain.port.in.DecisionExplanationUseCase;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the decision trace: what happened, and why (v2 batch 9).
 *
 * <p>Two verbs for two questions that must not be confused. {@code GET} replays
 * the stored rows and <b>recomputes nothing</b>, so a decision keeps being
 * explained by the rules it was taken under. {@code POST .../explain} adds the
 * analysis, which is necessarily computed against the rules in force now — and
 * the response's {@code provenance} is what lets a reader tell the two apart.
 *
 * <p>Secured by {@code hasRole("ADMIN")} in {@code SecurityConfig} like every
 * {@code /v1/admin/**} route: a trace names route examples, matched entries and
 * other requests' correlation ids, so it is strictly more sensitive than the
 * completions endpoint it explains. {@code POST .../explain} is additionally
 * rate-limited, because a prompt explanation costs n + 1 embedding calls against
 * the same local model that serves traffic.
 */
@RestController
@RequestMapping("/v1/admin/decisions")
class AdminDecisionController {

  /** Enough to see a pattern, small enough that no page is expensive. */
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 200;

  private final DecisionExplanationUseCase useCase;

  AdminDecisionController(DecisionExplanationUseCase useCase) {
    this.useCase = useCase;
  }

  /**
   * Recent decisions, newest first — the history the dashboard panel lists.
   *
   * <p>Not in the original plan, which specified only the two endpoints below
   * (D34): "detail on click" needs something to click, and building that list in
   * the browser from single-decision lookups would mean knowing the ids already.
   */
  @GetMapping
  List<DecisionView> recent(@RequestParam(required = false) Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    return useCase.recent(size).stream().map(DecisionView::of).toList();
  }

  @GetMapping("/{correlationId}")
  ResponseEntity<?> byCorrelationId(@PathVariable String correlationId) {
    return useCase.find(correlationId)
        .<ResponseEntity<?>>map(decision -> ResponseEntity.ok(DecisionView.of(decision)))
        .orElseGet(() -> notFound(correlationId));
  }

  /**
   * Explains a past request (by correlation id) or a prompt (on the fly).
   *
   * <p>Exactly one of the two: a body with both has not decided which question
   * it is asking, and a body with neither is asking nothing.
   */
  @PostMapping("/explain")
  ResponseEntity<?> explain(@RequestBody(required = false) ExplainRequest body) {
    ExplainRequest request =
        body == null ? new ExplainRequest(null, null) : body;

    if (request.hasCorrelationId() == request.hasPrompt()) {
      return ResponseEntity.badRequest().body(ApiError.of(
          "provide exactly one of 'correlationId' or 'prompt'",
          "invalid_request_error", "invalid_explain_request"));
    }
    if (request.hasPrompt()) {
      return ResponseEntity.ok(
          ExplanationView.of(useCase.explainPrompt(request.prompt())));
    }
    return useCase.explain(request.correlationId())
        .<ResponseEntity<?>>map(explanation ->
            ResponseEntity.ok(ExplanationView.of(explanation)))
        .orElseGet(() -> notFound(request.correlationId()));
  }

  /**
   * A 404 that says what is actually true: nothing was recorded under that id.
   * That can mean the request never happened, that its decisions were purged, or
   * that decision recording is switched off — the message names the last one,
   * because it is the one an operator can fix.
   */
  private static ResponseEntity<?> notFound(String correlationId) {
    return ResponseEntity.status(404).body(ApiError.of(
        "no decision recorded for correlation id '" + correlationId
            + "' (purged, never recorded, or gatewai.decisions.enabled=false)",
        "invalid_request_error", "decision_not_found"));
  }
}
