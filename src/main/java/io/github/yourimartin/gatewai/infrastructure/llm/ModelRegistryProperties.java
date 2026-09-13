package io.github.yourimartin.gatewai.infrastructure.llm;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.yourimartin.gatewai.domain.model.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.EnergySource;
import io.github.yourimartin.gatewai.domain.model.ModelTier;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gatewai.models")
class ModelRegistryProperties {

  private Map<String, ModelEntry> registry = new LinkedHashMap<>();

  Map<String, ModelEntry> getRegistry() {
    return registry;
  }

  void setRegistry(Map<String, ModelEntry> registry) {
    this.registry = registry;
  }

  static class ModelEntry {

    private String provider;
    private String modelId;
    private double costPer1kTokens;
    private Energy energy = new Energy();
    private ModelTier tier;

    String getProvider() {
      return provider;
    }

    void setProvider(String provider) {
      this.provider = provider;
    }

    String getModelId() {
      return modelId;
    }

    void setModelId(String modelId) {
      this.modelId = modelId;
    }

    double getCostPer1kTokens() {
      return costPer1kTokens;
    }

    void setCostPer1kTokens(double costPer1kTokens) {
      this.costPer1kTokens = costPer1kTokens;
    }

    Energy getEnergy() {
      return energy;
    }

    void setEnergy(Energy energy) {
      this.energy = energy;
    }

    ModelTier getTier() {
      return tier;
    }

    void setTier(ModelTier tier) {
      this.tier = tier;
    }
  }

  /**
   * {@code gatewai.models.registry.<key>.energy.*} — the prefill/decode split and
   * its provenance (v3 lot C.4). Every coefficient shipped or documented has a
   * source and a read-date in {@code docs/technical/green-accounting.md}.
   */
  static class Energy {

    private double prefillKwhPer1kPromptTokens;
    private double decodeKwhPer1kCompletionTokens;
    private double fixedKwhPerRequest;
    private EnergySource source;
    private boolean includesDatacenterOverhead;

    double getPrefillKwhPer1kPromptTokens() {
      return prefillKwhPer1kPromptTokens;
    }

    void setPrefillKwhPer1kPromptTokens(double prefillKwhPer1kPromptTokens) {
      this.prefillKwhPer1kPromptTokens = prefillKwhPer1kPromptTokens;
    }

    double getDecodeKwhPer1kCompletionTokens() {
      return decodeKwhPer1kCompletionTokens;
    }

    void setDecodeKwhPer1kCompletionTokens(double decodeKwhPer1kCompletionTokens) {
      this.decodeKwhPer1kCompletionTokens = decodeKwhPer1kCompletionTokens;
    }

    double getFixedKwhPerRequest() {
      return fixedKwhPerRequest;
    }

    void setFixedKwhPerRequest(double fixedKwhPerRequest) {
      this.fixedKwhPerRequest = fixedKwhPerRequest;
    }

    EnergySource getSource() {
      return source;
    }

    void setSource(EnergySource source) {
      this.source = source;
    }

    boolean isIncludesDatacenterOverhead() {
      return includesDatacenterOverhead;
    }

    void setIncludesDatacenterOverhead(boolean includesDatacenterOverhead) {
      this.includesDatacenterOverhead = includesDatacenterOverhead;
    }

    /** The domain value object this configuration describes. */
    EnergyProfile toProfile() {
      return new EnergyProfile(prefillKwhPer1kPromptTokens,
          decodeKwhPer1kCompletionTokens, fixedKwhPerRequest, source,
          includesDatacenterOverhead);
    }
  }
}
