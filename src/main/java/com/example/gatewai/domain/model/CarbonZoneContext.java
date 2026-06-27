package com.example.gatewai.domain.model;

/**
 * Propagates the grid zone chosen by carbon-aware dispatch (Phase 4.4) down the
 * synchronous completion flow via a {@link ScopedValue}, so the same
 * {@code complete()} path accounts emissions at the selected zone's intensity
 * without any extra parameters. Unbound on the normal (sync) path.
 */
public final class CarbonZoneContext {

  /** Bound by the dispatch worker around a deferred execution. */
  public static final ScopedValue<String> CURRENT = ScopedValue.newInstance();

  private CarbonZoneContext() {
  }
}
