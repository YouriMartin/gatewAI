package io.github.yourimartin.gatewai.adapter.in.web.admin;

import io.github.yourimartin.gatewai.domain.model.client.ClientMode;

/** Body for changing a client's mode (v4 D.1): {@code {"mode": "SHADOW"}}. */
public record ChangeModeRequest(ClientMode mode) {}
