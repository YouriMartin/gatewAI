package io.github.yourimartin.gatewai.domain.model.llm;

import io.github.yourimartin.gatewai.domain.model.carbon.EnergyProfile;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergySource;
import io.github.yourimartin.gatewai.domain.model.routing.ModelTier;

/**
 * A model the gateway can route to, with its cost and its energy profile.
 *
 * <p>Energy lives in an {@link EnergyProfile} (v3 lot C.4): a prefill/decode split
 * with a provenance label, rather than one scalar per 1000 tokens. The label is what
 * stops a report presenting an unaccounted model as a measured zero (lot C.1).
 *
 * @param key             registry key (stable identifier in configuration)
 * @param provider        provider name (e.g. {@code anthropic}, {@code ollama})
 * @param modelId         provider-specific model id sent on the wire
 * @param costPer1kTokens monetary cost per 1000 tokens, in the billing currency
 * @param energy          how much electricity one inference draws, and where that
 *                        estimate comes from; {@code null} means excluded from scope
 * @param tier            complexity tier used by the router
 */
public record ModelDefinition(
    String key,
    String provider,
    String modelId,
    double costPer1kTokens,
    EnergyProfile energy,
    ModelTier tier
) {

  /**
   * The model id that asks gatewAI to route (v4 B.2): listed first by
   * {@code GET /v1/models}, never a registry entry. Like any unregistered name,
   * it is classified; reserving it keeps a registry entry from shadowing it.
   */
  public static final String ROUTED_MODEL_ID = "auto";

  public ModelDefinition {
    if (energy == null) {
      energy = EnergyProfile.NOT_ACCOUNTED;
    }
  }

  /** Shorthand for {@code energy().source()}, the label every renderer prints. */
  public EnergySource energySource() {
    return energy.source();
  }
}
