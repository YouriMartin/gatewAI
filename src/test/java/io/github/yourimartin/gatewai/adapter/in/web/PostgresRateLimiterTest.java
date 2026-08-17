package io.github.yourimartin.gatewai.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The property that only a real database can show (v3 lot B.3): the limit belongs
 * to the <b>cluster</b>, not to a process. Two {@link PostgresRateLimiter}
 * instances stand in for two replicas — they are two proxy managers over one
 * DataSource, which is exactly what two nodes are.
 *
 * <p>Needs Postgres, so {@code @Tag("integration")}: run with
 * {@code ./mvnw -Pit test}. The in-memory store keeps its own unit test, whose
 * assertions this batch left untouched.
 */
@Tag("integration")
@SpringBootTest(properties = "spring.profiles.active=mock")
class PostgresRateLimiterTest {

  @Autowired
  private DataSource dataSource;

  @Autowired
  private JdbcTemplate jdbc;

  private RateLimitProperties properties;
  private String client;

  @BeforeEach
  void setUp() {
    properties = new RateLimitProperties();
    properties.setStore(RateLimitProperties.Store.POSTGRES);
    // A fresh id per test: buckets are keyed by client, so this is isolation.
    client = "it-" + UUID.randomUUID();
  }

  @AfterEach
  void removeTheBucket() {
    jdbc.update("DELETE FROM " + PostgresRateLimiter.TABLE
        + " WHERE " + PostgresRateLimiter.ID_COLUMN + " = ?", client);
  }

  private RateLimiter node() {
    return new PostgresRateLimiter(properties, dataSource);
  }

  @Test
  void twoInstancesShareOneQuotaInsteadOfHavingOneEach() {
    properties.setRequestsPerMinute(3);
    RateLimiter nodeA = node();
    RateLimiter nodeB = node();

    // Three requests spread over both nodes exhaust the client's quota.
    assertTrue(nodeA.tryAcquire(client).allowed());
    assertTrue(nodeB.tryAcquire(client).allowed());
    assertTrue(nodeA.tryAcquire(client).allowed());

    // The fourth is refused wherever it lands — with the in-memory store each
    // node would still have two tokens left, which is the bug this replaces.
    RateLimitResult blocked = nodeB.tryAcquire(client);
    assertFalse(blocked.allowed());
    assertTrue(blocked.retryAfterSeconds() >= 1);
    assertFalse(nodeA.tryAcquire(client).allowed());
  }

  @Test
  void bucketsAreIsolatedPerClient() {
    properties.setRequestsPerMinute(1);
    RateLimiter limiter = node();
    String other = "it-" + UUID.randomUUID();

    try {
      assertTrue(limiter.tryAcquire(client).allowed());
      assertFalse(limiter.tryAcquire(client).allowed());
      assertTrue(limiter.tryAcquire(other).allowed(),
          "one client's exhausted quota must not throttle another");
    } finally {
      jdbc.update("DELETE FROM " + PostgresRateLimiter.TABLE
          + " WHERE " + PostgresRateLimiter.ID_COLUMN + " = ?", other);
    }
  }

  @Test
  void changingTheLimitReplacesTheStoredConfiguration() {
    // The gotcha of a persisted bucket: it carries the bandwidth it was created
    // with. Without an implicit configuration replacement keyed on the limit,
    // editing gatewai.ratelimit.requests-per-minute would silently do nothing
    // until someone deleted the rows.
    properties.setRequestsPerMinute(1);
    RateLimiter limiter = node();
    assertTrue(limiter.tryAcquire(client).allowed());
    assertFalse(limiter.tryAcquire(client).allowed());

    properties.setRequestsPerMinute(5);

    assertTrue(limiter.tryAcquire(client).allowed(),
        "raising the limit must credit the new headroom, not wait for a refill");
  }
}
