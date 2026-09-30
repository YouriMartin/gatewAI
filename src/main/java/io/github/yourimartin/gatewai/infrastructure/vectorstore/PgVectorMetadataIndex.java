package io.github.yourimartin.gatewai.infrastructure.vectorstore;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Indexes the vector store's metadata so a per-conversation lookup is exact
 * (ADR 0014, v4 A.2).
 *
 * <p>The semantic cache filters every lookup on a conversation scope, and most
 * scopes hold a handful of entries. pgvector's HNSW index finds its nearest
 * neighbours <b>first</b> and filters afterwards, so a scope's entry that is not
 * among them is missed. Worse, A.2 makes a popular last turn ("give me an
 * example in Java") be stored once per scope: thousands of identical vectors,
 * a region of the graph the search does not leave. Measured on pgvector 0.8.3
 * with 3,000 such duplicates, a per-scope lookup found its entry in <b>3 of 20</b>
 * scopes, with or without {@code hnsw.iterative_scan}.
 *
 * <p>A GIN {@code jsonb_path_ops} index serves exactly the filter
 * {@code PgVectorStore} issues ({@code metadata::jsonb @@ jsonpath}). With it
 * the planner reads a small scope through this index and computes the distances
 * exactly: <b>20 of 20</b>, 0.18 ms. For a large scope (every single-turn request
 * without a system prompt shares one) it keeps choosing HNSW, from statistics.
 *
 * <p>Created here rather than by Flyway because {@code vector_store} belongs to
 * Spring AI ({@code initialize-schema}), which creates it after Flyway has run.
 * Only when Spring AI manages the schema: an operator who manages it is told
 * the statement instead. The cache itself never sees this class — it depends on
 * the {@code VectorStore} interface only (ADR 0005).
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.vectorstore.pgvector", name = "initialize-schema",
    havingValue = "true")
class PgVectorMetadataIndex implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(PgVectorMetadataIndex.class);

  /** Identifiers come from configuration and end up in DDL: plain names only. */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  private final JdbcTemplate jdbc;
  private final String schema;
  private final String table;

  PgVectorMetadataIndex(JdbcTemplate jdbc,
                        @Value("${spring.ai.vectorstore.pgvector.schema-name:public}")
                        String schema,
                        @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
                        String table) {
    this.jdbc = jdbc;
    this.schema = schema;
    this.table = table;
  }

  @Override
  public void run(ApplicationArguments args) {
    String statement = statement();
    if (statement == null) {
      LOG.warn("Vector store name {}.{} is not a plain identifier; not creating the metadata"
          + " index. Per-conversation cache lookups may miss entries (ADR 0014).",
          schema, table);
      return;
    }
    try {
      jdbc.execute(statement);
    } catch (DataAccessException e) {
      // Two nodes booting together can race on IF NOT EXISTS; the next boot
      // finds the index. Never a reason to refuse to start.
      LOG.warn("Could not create the vector store metadata index ({}): {}", statement,
          e.getMessage());
    }
  }

  /** The DDL, or null when a configured name is not a plain identifier. */
  String statement() {
    if (!IDENTIFIER.matcher(schema).matches() || !IDENTIFIER.matcher(table).matches()) {
      return null;
    }
    return "CREATE INDEX IF NOT EXISTS " + table + "_metadata_path_idx ON " + schema + "."
        + table + " USING gin ((metadata::jsonb) jsonb_path_ops)";
  }
}
