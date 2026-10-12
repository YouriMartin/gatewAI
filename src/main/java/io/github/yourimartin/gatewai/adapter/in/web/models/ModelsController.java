package io.github.yourimartin.gatewai.adapter.in.web.models;

import java.util.ArrayList;
import java.util.List;

import io.github.yourimartin.gatewai.adapter.in.web.error.ApiError;
import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.port.in.ListModelsUseCase;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI model discovery (v4 B.2). Chat UIs fill their model picker from
 * {@code GET /v1/models}; without it they show an empty list.
 *
 * <p>The list is {@code auto} first — "let gatewAI route" — then every registry
 * entry in declaration order. Each listed id is exactly what pins a request; any
 * other name, {@code auto} included, is classified. Secured like the rest of
 * {@code /v1/**}; a {@code GET} is not counted by the rate limiter, which only
 * meters the calls that cost a model call.
 */
@RestController
class ModelsController {

  private final ListModelsUseCase useCase;

  ModelsController(ListModelsUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping("/v1/models")
  ModelListView list() {
    List<ModelView> data = new ArrayList<>();
    data.add(ModelView.routed());
    useCase.models().stream().map(ModelView::of).forEach(data::add);
    return ModelListView.of(data);
  }

  /**
   * One entry, or a 404 in the OpenAI envelope. The catch-all segment accepts
   * ids that contain a slash ({@code org/model}, as vLLM and Hugging Face name
   * them); it arrives with its leading slash, which is dropped.
   */
  @GetMapping("/v1/models/{*modelId}")
  ResponseEntity<?> retrieve(@PathVariable String modelId) {
    String id = modelId.startsWith("/") ? modelId.substring(1) : modelId;
    if (ModelDefinition.ROUTED_MODEL_ID.equals(id)) {
      return ResponseEntity.ok(ModelView.routed());
    }
    return useCase.find(id)
        .<ResponseEntity<?>>map(model -> ResponseEntity.ok(ModelView.of(model)))
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
            "The model '" + id + "' does not exist.", "invalid_request_error", "model",
            "model_not_found")));
  }
}
