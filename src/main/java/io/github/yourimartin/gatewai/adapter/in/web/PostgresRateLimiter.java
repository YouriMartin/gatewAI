package io.github.yourimartin.gatewai.adapter.in.web;

import javax.sql.DataSource;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token buckets in PostgreSQL, so the limit is the <b>cluster's</b> and not each
 * process's (v3 lot B.3).
 *
 * <p>Bucket4j's {@code SELECT … FOR UPDATE} strategy, keyed on the client id as a
 * string. Two choices worth knowing:
 *
 * <ul>
 *   <li><b>{@code FOR UPDATE}, not an advisory lock.</b> The advisory-lock
 *       strategy locks on a {@code bigint}, so a string client id would have to be
 *       hashed to 64 bits — and a collision there would silently merge two
 *       tenants' quotas. Row locking takes the id as it is. It also means
 *       concurrent requests <em>from one client</em> serialize on that client's
 *       row, which is what a shared counter has to do; different clients never
 *       touch the same row.</li>
 *   <li><b>No Redis.</b> The whole point of lot B: the gateway already requires
 *       this database, and a second infra container would be the price of a
 *       millisecond.</li>
 * </ul>
 *
 * <p>Fails <b>open</b>. If the bucket cannot be read, the request is allowed and
 * the failure is logged: a limiter whose bookkeeping is unavailable should not
 * turn that into an outage. Largely theoretical — API-key authentication reads the
 * same database one filter earlier, so a database that cannot serve this cannot
 * serve the request either.
 */
class PostgresRateLimiter implements RateLimiter {

  private static final Logger LOG =
      LoggerFactory.getLogger(PostgresRateLimiter.class);

  static final String TABLE = "rate_limit_bucket";
  static final String ID_COLUMN = "client_id";
  static final String STATE_COLUMN = "state";

  private final RateLimitProperties properties;
  private final ProxyManager<String> buckets;

  PostgresRateLimiter(RateLimitProperties properties, DataSource dataSource) {
    this.properties = properties;
    this.buckets = Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
        .primaryKeyMapper(PrimaryKeyMapper.STRING)
        .table(TABLE)
        .idColumn(ID_COLUMN)
        .stateColumn(STATE_COLUMN)
        .build();
    LOG.info("Rate limiting on PostgreSQL ({} req/min, shared across instances)",
        properties.getRequestsPerMinute());
  }

  @Override
  public RateLimitResult tryAcquire(String clientId) {
    try {
      return RateLimiter.resultOf(
          bucket(clientId).tryConsumeAndReturnRemaining(1));
    } catch (RuntimeException e) {
      LOG.warn("Rate limit check failed, allowing the request: {}", e.toString());
      return RateLimitResult.granted();
    }
  }

  /**
   * The client's bucket, with the configured limit.
   *
   * <p>The configuration version is the limit itself. A stored bucket carries the
   * bandwidth it was created with, so raising
   * {@code gatewai.ratelimit.requests-per-minute} and restarting would otherwise
   * keep enforcing the old value until someone deleted the rows by hand — a
   * setting that silently does nothing, which is the worst kind. Making the
   * version the number means any change to it replaces the stored configuration.
   *
   * <p>{@code ADDITIVE} for the inheritance: an operator who raises a limit
   * because clients are being throttled expects the headroom now, so the
   * difference is credited immediately rather than waiting out a refill window.
   * Lowering it debits the same way, which is equally what you want from a
   * setting you just tightened.
   */
  private BucketProxy bucket(String clientId) {
    int perMinute = properties.getRequestsPerMinute();
    return buckets.builder()
        .withImplicitConfigurationReplacement(
            Math.max(1L, perMinute), TokensInheritanceStrategy.ADDITIVE)
        .build(clientId, () -> BucketConfiguration.builder()
            .addLimit(RateLimiter.bandwidth(perMinute))
            .build());
  }
}
