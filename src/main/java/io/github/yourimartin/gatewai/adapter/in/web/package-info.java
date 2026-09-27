/**
 * Inbound web adapters: REST controllers (OpenAI-compatible ingress).
 * Calls inbound ports from domain.
 *
 * <p>Split by concern: {@code chat} (OpenAI ingress + async completions),
 * {@code admin} (the {@code /v1/admin/**} API), {@code report} (green reporting),
 * {@code security} (filter chain, API-key auth, correlation id), {@code ratelimit},
 * {@code error} (OpenAI error envelope) and {@code nativehints} (GraalVM hints).
 */
package io.github.yourimartin.gatewai.adapter.in.web;
