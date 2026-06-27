package com.example.gatewai.infrastructure.persistence;

import com.example.gatewai.domain.model.CreatedApiClient;
import com.example.gatewai.domain.port.in.ManageApiClientsUseCase;
import com.example.gatewai.domain.port.out.ApiClientRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Bootstraps the first admin client at startup so the system is usable without
 * hand-inserting a key (Phase 5.1). If no admin exists, creates one and logs its
 * raw key exactly once — copy it, it is never shown again.
 */
@Component
class AdminSeedRunner implements ApplicationRunner {

  private static final Logger LOG =
      LoggerFactory.getLogger(AdminSeedRunner.class);

  private final ApiClientRepository repository;
  private final ManageApiClientsUseCase manageApiClients;

  AdminSeedRunner(ApiClientRepository repository,
                  ManageApiClientsUseCase manageApiClients) {
    this.repository = repository;
    this.manageApiClients = manageApiClients;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (repository.adminExists()) {
      return;
    }
    CreatedApiClient created = manageApiClients.create("bootstrap-admin", true);
    LOG.warn("No admin client found — created '{}'. "
            + "Admin API key (shown ONCE, copy it now): {}",
        created.client().name(), created.rawKey());
  }
}
