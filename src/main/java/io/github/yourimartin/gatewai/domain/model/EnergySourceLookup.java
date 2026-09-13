package io.github.yourimartin.gatewai.domain.model;

/**
 * Resolves how a served model's energy is accounted, by provider model id
 * (v3 lot C.1). Implemented over the model registry; kept as a domain interface
 * so {@link ReportAggregator} stays in the domain and depends on no port.
 *
 * <p>Reporting looks the label up in the <em>current</em> configuration, because
 * persisted rows do not carry it yet — a model that has since left the registry
 * resolves to whatever the implementation chooses as its fallback. Storing the
 * label per row (so history stops depending on today's config) is v3 lot C.5.
 */
@FunctionalInterface
public interface EnergySourceLookup {

  /**
   * The energy provenance of the model behind {@code modelId}.
   *
   * @param modelId provider-specific model id as recorded on the request
   * @return the label, never {@code null}
   */
  EnergySource forModelId(String modelId);
}
