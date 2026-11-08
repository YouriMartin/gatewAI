package io.github.yourimartin.gatewai.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * D.2 promises no new plaintext: both answers are compared in memory and
 * discarded. This holds the table and the entity to it — a column able to carry
 * an answer cannot be added without this test changing with it.
 */
class WouldHitAssessmentSchemaTest {

  /** Every column, with its declared type: identifiers, numbers and labels only. */
  private static final Map<String, String> COLUMNS = Map.ofEntries(
      Map.entry("id", "uuid"),
      Map.entry("correlation_id", "varchar(64)"),
      Map.entry("client_id", "varchar(255)"),
      Map.entry("created_at", "timestamp(6)"),
      Map.entry("answer_similarity", "float8"),
      Map.entry("beyond_window", "boolean"),
      Map.entry("embedding_model", "varchar(255)"),
      Map.entry("judge_outcome", "varchar(16)"),
      Map.entry("judge_verdict", "varchar(16)"),
      Map.entry("judge_model_id", "varchar(255)"),
      Map.entry("rubric_version", "varchar(32)"),
      Map.entry("judge_correlation_id", "varchar(64)"));

  /** The entity's String fields: ids and names, none of them an answer. */
  private static final Set<String> STRING_FIELDS = Set.of("correlationId", "clientId",
      "embeddingModel", "judgeModelId", "rubricVersion", "judgeCorrelationId");

  @Test
  void theMigrationDeclaresNoColumnThatCouldHoldAnswerText() throws IOException {
    Map<String, String> declared = columns(migration());

    assertThat(declared).isEqualTo(COLUMNS);
    assertThat(declared.values()).noneMatch(type -> type.startsWith("text")
        || type.startsWith("jsonb") || type.startsWith("bytea"));
  }

  @Test
  void theEntityHasNoFieldBeyondIdentifiersNumbersAndLabels() {
    Set<String> strings = Arrays.stream(WouldHitAssessmentEntity.class.getDeclaredFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .filter(field -> field.getType() == String.class)
        .map(Field::getName)
        .collect(Collectors.toSet());

    assertThat(strings).isEqualTo(STRING_FIELDS);
  }

  private static String migration() throws IOException {
    try (InputStream in = WouldHitAssessmentSchemaTest.class.getResourceAsStream(
        "/db/migration/V15__would_hit_assessment.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** The columns of every CREATE TABLE in {@code sql}, comments stripped. */
  private static Map<String, String> columns(String sql) {
    String code = sql.replaceAll("--[^\n]*", "");
    Matcher table = Pattern.compile("CREATE TABLE[^(]*\\((.*?)\\);", Pattern.DOTALL)
        .matcher(code);
    Map<String, String> columns = new LinkedHashMap<>();
    while (table.find()) {
      for (String line : table.group(1).split(",\n")) {
        String[] parts = line.strip().split("\\s+");
        if (parts.length >= 2 && !"CONSTRAINT".equals(parts[0])) {
          columns.put(parts[0], parts[1]);
        }
      }
    }
    return columns;
  }
}
