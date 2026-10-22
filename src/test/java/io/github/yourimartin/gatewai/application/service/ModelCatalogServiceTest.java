package io.github.yourimartin.gatewai.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.llm.ModelDefinition;
import io.github.yourimartin.gatewai.domain.model.llm.ModelPricing;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;
import io.github.yourimartin.gatewai.domain.port.out.ModelRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ModelCatalogServiceTest {

  private static final ModelDefinition LOCAL =
      new ModelDefinition("local", "ollama", "qwen2.5:3b", ModelPricing.NONE, null, ModelTier.LOCAL);
  private static final ModelDefinition PREMIUM = new ModelDefinition(
      "premium", "anthropic", "claude-sonnet-4-5", ModelPricing.blended(0.015), null, ModelTier.CLOUD_PREMIUM);

  @Mock
  private ModelRegistry modelRegistry;

  @Test
  void listsTheRegistryInDeclarationOrder() {
    when(modelRegistry.allModels()).thenReturn(List.of(LOCAL, PREMIUM));

    assertEquals(List.of(LOCAL, PREMIUM), new ModelCatalogService(modelRegistry).models());
  }

  @Test
  void findsByTheIdThatPins() {
    // The router pins through findByModelId: a listed id must resolve the same way.
    when(modelRegistry.findByModelId("qwen2.5:3b")).thenReturn(Optional.of(LOCAL));

    ModelCatalogService service = new ModelCatalogService(modelRegistry);

    assertEquals(Optional.of(LOCAL), service.find("qwen2.5:3b"));
    assertTrue(service.find("local").isEmpty(), "the registry key is not a model id");
  }
}
