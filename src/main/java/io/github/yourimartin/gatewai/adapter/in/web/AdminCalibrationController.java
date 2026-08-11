package io.github.yourimartin.gatewai.adapter.in.web;

import java.util.List;

import io.github.yourimartin.gatewai.domain.model.CalibrationState;
import io.github.yourimartin.gatewai.domain.port.in.CalibrationUseCase;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 */
@RestController
@RequestMapping("/v1/admin/calibration")
class AdminCalibrationController {

  private final CalibrationUseCase useCase;

  AdminCalibrationController(CalibrationUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  List<CalibrationView> get() {
    return views(useCase.states());
  }

  @PostMapping
  ResponseEntity<?> recalibrate(@RequestBody(required = false)
                                RecalibrateRequest body) {
    try {
      RecalibrateRequest request =
          body == null ? new RecalibrateRequest(null, null) : body;
      return ResponseEntity.ok(views(
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

  private static List<CalibrationView> views(List<CalibrationState> states) {
    return states.stream().map(CalibrationView::of).toList();
  }
}
