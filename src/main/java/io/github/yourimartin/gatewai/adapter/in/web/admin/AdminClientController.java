package io.github.yourimartin.gatewai.adapter.in.web.admin;

import java.util.List;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.client.CreatedApiClient;
import io.github.yourimartin.gatewai.domain.port.in.ManageApiClientsUseCase;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin API for managing clients / keys (Phase 5.1). Secured by
 * {@code hasRole("ADMIN")} in {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/v1/admin/clients")
class AdminClientController {

  private final ManageApiClientsUseCase useCase;

  AdminClientController(ManageApiClientsUseCase useCase) {
    this.useCase = useCase;
  }

  @PostMapping
  ResponseEntity<CreatedClientView> create(
      @RequestBody CreateClientRequest request) {
    CreatedApiClient created = useCase.create(request.name(), request.admin());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(CreatedClientView.of(created));
  }

  @GetMapping
  List<ApiClientView> list() {
    return useCase.list().stream().map(ApiClientView::of).toList();
  }

  @PostMapping("/{id}/revoke")
  ResponseEntity<Void> revoke(@PathVariable UUID id) {
    useCase.revoke(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Puts a client in {@code ENFORCE} or {@code SHADOW} (v4 D.1), from its next
   * request on. The change is recorded with the id of the admin client making it.
   */
  @PutMapping("/{id}/mode")
  ResponseEntity<ApiClientView> changeMode(@PathVariable UUID id,
                                           @RequestBody ChangeModeRequest request,
                                           Authentication admin) {
    if (request == null || request.mode() == null) {
      return ResponseEntity.badRequest().build();
    }
    return useCase.changeMode(id, request.mode(), admin.getName())
        .map(client -> ResponseEntity.ok(ApiClientView.of(client)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
