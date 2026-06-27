package com.example.gatewai.adapter.in.web;

import com.example.gatewai.domain.port.in.RoutingConfigUseCase;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API to read and hot-tune the routing config (Phase 5.2). Secured by
 * {@code hasRole("ADMIN")} in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/v1/admin/routing")
class AdminRoutingController {

  private final RoutingConfigUseCase useCase;

  AdminRoutingController(RoutingConfigUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  RoutingConfigView get() {
    return RoutingConfigView.of(useCase.current());
  }

  @PutMapping
  ResponseEntity<RoutingConfigView> update(
      @RequestBody RoutingConfigView body) {
    try {
      useCase.update(body.toDomain());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(RoutingConfigView.of(useCase.current()));
  }
}
