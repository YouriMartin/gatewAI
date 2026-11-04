package io.github.yourimartin.gatewai.adapter.in.web.admin;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import io.github.yourimartin.gatewai.domain.port.in.ShadowAccuracyUseCase;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shadow mode, read back (v4 D.2). Secured by {@code hasRole("ADMIN")} in
 * {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/v1/admin/shadow")
class AdminShadowController {

  private final ShadowAccuracyUseCase useCase;

  AdminShadowController(ShadowAccuracyUseCase useCase) {
    this.useCase = useCase;
  }

  /** Estimated wrong-hit rate per client and judge over {@code [from, to)}. */
  @GetMapping("/wrong-hits")
  ResponseEntity<List<WrongHitView>> wrongHits(@RequestParam String from,
                                               @RequestParam String to) {
    Instant start;
    Instant end;
    try {
      start = Instant.parse(from);
      end = Instant.parse(to);
    } catch (DateTimeParseException e) {
      return ResponseEntity.badRequest().build();
    }
    if (!start.isBefore(end)) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(useCase.wrongHitRates(start, end).stream()
        .map(WrongHitView::of).toList());
  }
}
