package com.example.gatewai.adapter.in.web;

import java.util.Collection;
import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

final class ApiKeyAuthentication extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  private final String clientId;
  private final String clientName;

  ApiKeyAuthentication(String clientId, String clientName) {
    this(clientId, clientName, List.of());
  }

  ApiKeyAuthentication(String clientId, String clientName,
                       Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.clientId = clientId;
    this.clientName = clientName;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Object getPrincipal() {
    return clientId;
  }

  String getClientId() {
    return clientId;
  }

  String getClientName() {
    return clientName;
  }
}
