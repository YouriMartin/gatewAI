package io.github.yourimartin.gatewai.domain.model;

/**
 * A model the gateway can route to, with its cost and energy coefficients.
 *
 * <p>{@code energySource} labels where {@code energyIntensity} comes from, so a
 * report can never present an unaccounted model as a measured zero (v3 lot C.1).
 * The two fields are kept consistent by construction: a
 * {@link EnergySource#NOT_ACCOUNTED} model must carry a zero coefficient, and a
 * zero coefficient with no declared source <em>is</em> {@code NOT_ACCOUNTED}.
 *
 * @param key             registry key (stable identifier in configuration)
 * @param provider        provider name (e.g. {@code anthropic}, {@code ollama})
 * @param modelId         provider-specific model id sent on the wire
 * @param costPer1kTokens monetary cost per 1000 tokens, in the billing currency
 * @param energyIntensity estimated electrical energy per 1000 tokens, in kWh
 *                        (energy only — carbon is energy × grid intensity);
 *                        {@code 0} when the energy is not accounted at all
 * @param energySource    provenance of {@code energyIntensity}; {@code null} is
 *                        resolved from the coefficient (0 → {@code NOT_ACCOUNTED},
 *                        otherwise {@code MODELLED})
 * @param tier            complexity tier used by the router
 */
public record ModelDefinition(
    String key,
    String provider,
    String modelId,
    double costPer1kTokens,
    double energyIntensity,
    EnergySource energySource,
    ModelTier tier
) {

  public ModelDefinition {
    if (energySource == null) {
      energySource = energyIntensity == 0.0
          ? EnergySource.NOT_ACCOUNTED
          : EnergySource.MODELLED;
    }
    if (energySource == EnergySource.NOT_ACCOUNTED && energyIntensity != 0.0) {
      throw new IllegalArgumentException(
          "Model '" + key + "' declares energy-source=not-accounted but a non-zero "
              + "energy-intensity (" + energyIntensity + "): an unaccounted model is "
              + "booked at zero. Drop the coefficient or declare its source.");
    }
  }
}
