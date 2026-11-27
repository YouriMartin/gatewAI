package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.github.yourimartin.gatewai.domain.model.carbon.CarbonZoneSource;
import io.github.yourimartin.gatewai.domain.model.carbon.EnergySource;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenMetrics;
import io.github.yourimartin.gatewai.domain.model.carbon.GreenProvenance;
import io.github.yourimartin.gatewai.domain.model.carbon.RegionProvenance;
import io.github.yourimartin.gatewai.domain.model.client.ClientMode;
import io.github.yourimartin.gatewai.domain.model.llm.PricingBasis;
import io.github.yourimartin.gatewai.domain.model.llm.TokenUsage;
import io.github.yourimartin.gatewai.domain.model.report.RequestLog;
import io.github.yourimartin.gatewai.domain.model.shadow.CounterfactualOutcome;
import io.github.yourimartin.gatewai.domain.model.shadow.ShadowCounterfactual;
import io.github.yourimartin.gatewai.domain.port.out.RequestLogRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code V17} on real SQL (v4 D.4, ADR 0020): a counterfactual round-trips with its
 * provenance, the table refuses what the ADR rules out, and {@code request_log}
 * stores the mode a request was served in.
 *
 * <p>Every row is written under a random correlation id and removed afterwards.
 * Needs Postgres, so {@code @Tag("integration")}: run with {@code ./mvnw -Pit test}.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.profiles.active=mock")
class ShadowCounterfactualPersistenceTest {

  private static final Instant AT = Instant.now().truncatedTo(ChronoUnit.MICROS);

  @Autowired
  private SpringDataShadowCounterfactualRepository counterfactuals;

  @Autowired
  private RequestLogRepository requestLogs;

  @Autowired
  private JdbcTemplate jdbc;

  private final String correlationId = "test-" + UUID.randomUUID();

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM shadow_counterfactual WHERE correlation_id = ?", correlationId);
    jdbc.update("DELETE FROM request_log WHERE correlation_id = ?", correlationId);
  }

  @Test
  void aRoutedCounterfactualRoundTripsWithEverythingItWasPricedUnder() {
    ShadowCounterfactual routed = new ShadowCounterfactual(UUID.randomUUID(), correlationId,
        "client-a", AT, CounterfactualOutcome.ROUTED, "qwen2.5:3b",
        new TokenUsage(120, 30, 151, 40),
        new GreenMetrics(0.0021, 0.00012, 0.051, 0, 0, PricingBasis.SPLIT, "EUR"),
        new GreenProvenance("vllm", "FR", 52.0, CarbonZoneSource.PROVIDER_REGION, null,
            RegionProvenance.KNOWN, EnergySource.MODELLED, 1.2));

    counterfactuals.save(new ShadowCounterfactualEntity(routed));

    assertThat(counterfactuals.findById(routed.id()).orElseThrow().toDomain())
        .isEqualTo(routed);
  }

  @Test
  void aWouldHitRoundTripsAsFree() {
    ShadowCounterfactual hit = new ShadowCounterfactual(UUID.randomUUID(), correlationId,
        "client-a", AT, CounterfactualOutcome.WOULD_HIT, null, TokenUsage.of(80, 20),
        GreenMetrics.none("EUR"), GreenProvenance.UNKNOWN);

    counterfactuals.save(new ShadowCounterfactualEntity(hit));

    assertThat(counterfactuals.findById(hit.id()).orElseThrow().toDomain().metrics())
        .isEqualTo(GreenMetrics.none("EUR"));
  }

  @Test
  void theTableRefusesAWouldHitWithAModelOrACostAndAnUnknownOutcome() {
    assertThatThrownBy(() -> insert("WOULD_HIT", "qwen2.5:3b", 0))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert("WOULD_HIT", null, 0.001))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert("ROUTED", null, 0.001))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert("GUESSED", "m", 0.001))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void requestLogStoresTheModeItWasServedIn() {
    requestLogs.save(new RequestLog(UUID.randomUUID(), correlationId, AT, "gpt-5", "h",
        10, 5, 15, 3, "client-a", GreenMetrics.none("EUR"), GreenProvenance.UNKNOWN, false,
        null, ClientMode.SHADOW));

    assertThat(jdbc.queryForObject("SELECT mode FROM request_log WHERE correlation_id = ?",
        String.class, correlationId)).isEqualTo("SHADOW");
    assertThatThrownBy(() -> jdbc.update("UPDATE request_log SET mode = 'GUESSED' "
        + "WHERE correlation_id = ?", correlationId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insert(String outcome, String model, double cost) {
    jdbc.update("""
        INSERT INTO shadow_counterfactual (id, correlation_id, created_at, outcome, model,
            prompt_tokens, completion_tokens, total_tokens, cost, pricing_basis, currency,
            energy_kwh, grams_co2)
        VALUES (?, ?, now(), ?, ?, 1, 1, 2, ?, 'SPLIT', 'EUR', 0, 0)""",
        UUID.randomUUID(), correlationId, outcome, model, cost);
  }
}
