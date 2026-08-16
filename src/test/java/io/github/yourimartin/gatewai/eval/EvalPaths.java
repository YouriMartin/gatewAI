package io.github.yourimartin.gatewai.eval;

import java.nio.file.Path;

/** Where the evaluation harness reads fixtures from and writes reports to. */
final class EvalPaths {

  /** Classpath resource holding the recorded routing vectors. */
  static final String ROUTING_VECTORS = "/eval/fixtures/routing-vectors.json";

  /** Classpath resource holding the recorded query/entry similarities. */
  static final String CACHE_SIMILARITIES = "/eval/fixtures/cache-similarities.json";

  /** Source tree the recorder writes to, so fixtures land under version control. */
  static final Path FIXTURE_SOURCE_DIR =
      Path.of("src", "test", "resources", "eval", "fixtures");

  /** Where a run drops its report. Build output: never committed. */
  static final Path REPORT_DIR = Path.of("target", "eval");

  /** Printed whenever fixtures are missing or stale — the fix is one command. */
  static final String RECORD_COMMAND =
      "./mvnw test -Dtest=EvalFixtureRecorderTest -Deval.record=true";

  private EvalPaths() {
  }
}
