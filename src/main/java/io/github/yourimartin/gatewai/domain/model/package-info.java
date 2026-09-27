/**
 * Domain model: entities, value objects, records.
 * No framework dependencies allowed in this package or its subpackages.
 *
 * <p>Split by concern: {@code llm} (requests, responses, model registry entries),
 * {@code routing}, {@code calibration} (conformal), {@code decision} (traced
 * cache/routing decisions), {@code explanation} (attribution + counterfactuals),
 * {@code carbon} (energy, grid zones, green accounting), {@code report},
 * {@code client} (API clients), {@code dispatch} (deferred jobs) and
 * {@code context} (request and node identity).
 */
package io.github.yourimartin.gatewai.domain.model;
