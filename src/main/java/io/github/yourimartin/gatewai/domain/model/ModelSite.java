package io.github.yourimartin.gatewai.domain.model;

/**
 * A model together with the physical conditions it ran under (v3 lots C.2–C.4):
 * the grid that powered it and the efficiency of the datacenter that hosted it.
 *
 * <p>It exists so the two sides of the avoided figure can differ. The served model
 * and the premium baseline may sit behind providers in different regions, on
 * datacenters with different PUE — pricing the counterfactual at the served model's
 * grid is the wrong-grid error one step removed.
 *
 * @param model                    the model, or {@code null} when unknown
 * @param gridIntensityGramsPerKwh grid carbon intensity where it ran, gCO2/kWh
 * @param pue                      datacenter power usage effectiveness, or
 *                                 {@code null} to use the documented default
 */
public record ModelSite(ModelDefinition model, double gridIntensityGramsPerKwh, Double pue) {

  /** A site with no known datacenter efficiency; the default PUE applies. */
  public static ModelSite at(ModelDefinition model, double gridIntensityGramsPerKwh) {
    return new ModelSite(model, gridIntensityGramsPerKwh, null);
  }
}
