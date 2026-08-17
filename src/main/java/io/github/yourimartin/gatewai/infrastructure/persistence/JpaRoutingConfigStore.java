package io.github.yourimartin.gatewai.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import io.github.yourimartin.gatewai.domain.model.RoutingConfig;
import io.github.yourimartin.gatewai.domain.model.StoredRoutingConfig;
import io.github.yourimartin.gatewai.domain.port.out.RoutingConfigStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter for {@link RoutingConfigStore} (v3 lot B.1). */
@Component
class JpaRoutingConfigStore implements RoutingConfigStore {

  private static final Logger LOG =
      LoggerFactory.getLogger(JpaRoutingConfigStore.class);

  private final SpringDataRoutingConfigRepository repository;

  JpaRoutingConfigStore(SpringDataRoutingConfigRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<StoredRoutingConfig> load() {
    return repository.findById(RoutingConfigEntity.ROW_ID)
        .map(RoutingConfigEntity::toDomain);
  }

  @Override
  @Transactional
  public StoredRoutingConfig seedIfAbsent(RoutingConfig config,
                                          double cascadeMarginBand) {
    int inserted = repository.insertIfAbsent(
        config.strategy(),
        config.entryLengthThreshold(),
        config.premiumLengthThreshold(),
        RoutingConfigJson.keywordsToJson(config.premiumKeywords()),
        config.routeSimilarityThreshold(),
        RoutingConfigJson.routesToJson(config.routes()),
        cascadeMarginBand,
        Instant.now());
    if (inserted == 0) {
      LOG.info("Another instance wrote the routing config first; adopting it "
          + "instead of this node's defaults.");
    }
    return load().orElseThrow(() -> new IllegalStateException(
        "The routing config row is missing right after being inserted"));
  }

  @Override
  @Transactional
  public StoredRoutingConfig saveConfig(RoutingConfig config) {
    RoutingConfigEntity entity = locked();
    entity.applyConfig(config);
    return bump(entity);
  }

  @Override
  @Transactional
  public StoredRoutingConfig saveCascadeMarginBand(double cascadeMarginBand) {
    RoutingConfigEntity entity = locked();
    entity.applyCascadeMarginBand(cascadeMarginBand);
    return bump(entity);
  }

  /**
   * The row every write needs, locked. It is created at startup, so its absence
   * here means it was deleted underneath a running gateway — a loud failure is
   * the honest answer, because inserting a fresh row would mean inventing the
   * half of the configuration this call did not carry.
   */
  private RoutingConfigEntity locked() {
    return repository.findForUpdate().orElseThrow(() -> new IllegalStateException(
        "The routing config row is missing; it is written at startup"));
  }

  private StoredRoutingConfig bump(RoutingConfigEntity entity) {
    entity.nextRevision(Instant.now());
    return repository.saveAndFlush(entity).toDomain();
  }
}
