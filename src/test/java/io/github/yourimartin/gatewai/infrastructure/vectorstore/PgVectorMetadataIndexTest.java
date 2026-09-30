package io.github.yourimartin.gatewai.infrastructure.vectorstore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class PgVectorMetadataIndexTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  @Test
  void createsTheIndexThePgVectorFilterUses() {
    new PgVectorMetadataIndex(jdbc, "public", "vector_store")
        .run(new DefaultApplicationArguments());

    // Must match PgVectorStore's filter, metadata::jsonb @@ jsonpath, or the
    // planner has nothing to use it for.
    verify(jdbc).execute("CREATE INDEX IF NOT EXISTS vector_store_metadata_path_idx"
        + " ON public.vector_store USING gin ((metadata::jsonb) jsonb_path_ops)");
  }

  @Test
  void followsAConfiguredSchemaAndTable() {
    assertEquals("CREATE INDEX IF NOT EXISTS cache_metadata_path_idx ON gw.cache"
            + " USING gin ((metadata::jsonb) jsonb_path_ops)",
        new PgVectorMetadataIndex(jdbc, "gw", "cache").statement());
  }

  @Test
  void neverPutsAnArbitraryConfiguredNameIntoDdl() {
    PgVectorMetadataIndex index =
        new PgVectorMetadataIndex(jdbc, "public", "vector_store; DROP TABLE api_client");

    assertNull(index.statement());
    index.run(new DefaultApplicationArguments());
    verify(jdbc, never()).execute(anyString());
  }

  @Test
  void aFailureNeverStopsTheGatewayFromStarting() {
    doThrow(new DataIntegrityViolationException("duplicate key value"))
        .when(jdbc).execute(anyString());

    assertDoesNotThrow(() -> new PgVectorMetadataIndex(jdbc, "public", "vector_store")
        .run(new DefaultApplicationArguments()));
  }
}
