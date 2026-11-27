package io.github.yourimartin.gatewai.adapter.in.web.admin;

import java.util.List;
import java.util.stream.Stream;

import io.github.yourimartin.gatewai.adapter.in.web.error.ApiError;
import io.github.yourimartin.gatewai.domain.model.calibration.CalibrationState;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for the conformal calibrations (v2 batch 3). Secured by
 * {@code hasRole("ADMIN")} in {@code SecurityConfig}, like every
 * {@code /v1/admin/**} route.
 *
 * <p>{@code POST} is deliberately slow and deliberately manual: it classifies
 * every labelled prompt and embeds every labelled pair, which takes tens of
 * seconds against a local model. A threshold that governs what users are served
 * should move when someone decides it should, not when a timer fires.
 *
 * <p>{@code source=shadow} (v4 D.3, ADR 0019) fits the cache only, on the judge's
 * verdicts on shadow would-hits: for one client, or pooled into the global row.
 * It embeds nothing — the similarities are the ones the cache decided on.
 */
@RestController
@RequestMapping("/v1/admin/calibration")
class AdminCalibrationController {

  private final CalibrationUseCase useCase;

  AdminCalibrationController(CalibrationUseCase useCase) {
    this.useCase = useCase;
  }

  /** The global state of each target, then every client's own row (v4 D.3). */
  @GetMapping
  List<CalibrationView> get() {
    return all();
  }

  @PostMapping
  ResponseEntity<?> recalibrate(@RequestBody(required = false)
                                RecalibrateRequest body) {
    RecalibrateRequest request = body == null ? RecalibrateRequest.defaults() : body;
    String problem = request.problem();
    if (problem != null) {
      return ResponseEntity.badRequest().body(
          ApiError.of(problem, "invalid_request_error", "invalid_request"));
    }
    return request.isShadow() ? shadowFit(request) : labelledSetFit(request);
  }

  /**
   * Removes a client's own cache calibration (v4 D.3, ADR 0019): the client falls
   * back to the global one. 404 when it had none.
   */
  @DeleteMapping("/cache/{clientId}")
  ResponseEntity<?> removeClientCalibration(@PathVariable String clientId) {
    if (useCase.removeClientCalibration(clientId)) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.status(404).body(ApiError.of(
        "Client '" + clientId + "' has no cache calibration of its own",
        "invalid_request_error", "calibration_not_found"));
  }

  private ResponseEntity<?> labelledSetFit(RecalibrateRequest request) {
    try {
      return ResponseEntity.ok(withClients(
          useCase.recalibrate(request.routingAlpha(), request.cacheAlpha())));
    } catch (IllegalArgumentException e) {
      // An alpha outside (0,1): the request itself is wrong.
      return ResponseEntity.badRequest().body(
          ApiError.of(e.getMessage(), "invalid_request_error", "invalid_alpha"));
    } catch (IllegalStateException e) {
      // The labelled set cannot support what was asked — too few cases for that
      // alpha, or no route scores because another strategy is configured.
      return ResponseEntity.status(409).body(
          ApiError.of(e.getMessage(), "calibration_error", "calibration_failed"));
    }
  }

  /**
   * A cache fit on the judge's verdicts on shadow would-hits. A request the fit
   * cannot use (no judge named, alpha outside (0, 1)) is a 400; labels that cannot
   * support it (below the minimum sample) are a 409 whose message gives the counts.
   */
  private ResponseEntity<?> shadowFit(RecalibrateRequest request) {
    try {
      useCase.calibrateCacheOnShadow(request.client(), request.cacheAlpha(),
          request.judgeModel());
      return ResponseEntity.ok(all());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(
          ApiError.of(e.getMessage(), "invalid_request_error", "invalid_request"));
    } catch (IllegalStateException e) {
      return ResponseEntity.status(409).body(
          ApiError.of(e.getMessage(), "calibration_error", "calibration_failed"));
    }
  }

  private List<CalibrationView> all() {
    return withClients(useCase.states());
  }

  private List<CalibrationView> withClients(List<CalibrationState> global) {
    return Stream.concat(global.stream(), useCase.clientStates().stream())
        .map(CalibrationView::of)
        .toList();
  }
}
