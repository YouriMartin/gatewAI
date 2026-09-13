package io.github.yourimartin.gatewai.domain.model;

/**
 * How much of a reporting period's inference is covered by the emission totals
 * (v3 lot C.1) — derived from the {@link EnergySource} of the models that served
 * it, never configured.
 *
 * <p>It exists so a zero in a report is never ambiguous: {@link #ALL_EXCLUDED}
 * means "nothing was accounted", which is a different statement from
 * {@link #ALL_ACCOUNTED} with genuinely zero emissions.
 */
public enum EmissionsScope {

  /** No requests in the period — nothing to account either way. */
  NO_ACTIVITY,

  /** Every inference was served by a model with an energy coefficient. */
  ALL_ACCOUNTED,

  /** Some inference was served by models excluded from scope. */
  PARTIALLY_EXCLUDED,

  /** Every inference was served by models excluded from scope. */
  ALL_EXCLUDED
}
