package in.systemhalted.kisoku.runtime.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.testutil.DecisionTableFixtures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Throughput smoke test for the scalar columnar bulk kernel: a modest rule table (tens of thousands
 * of rows) scored against many inputs through the parallel path. Asserts completion and logs
 * evals/sec; it is a sanity check, not a benchmark SLA. Gated behind the scale flag.
 */
class ColumnarBulkKernelThroughputTest {

  private static final long TABLE_ROWS = 50_000L;
  private static final int INPUT_COLUMNS = 20;
  private static final int OUTPUT_COLUMNS = 5;
  private static final int INPUT_COUNT = 200_000;

  @Tag("scale")
  @Test
  void scoresManyInputsInParallel(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runScaleTests=true to enable this test.");

    Path csv =
        DecisionTableFixtures.writeLargeTable(tempDir, TABLE_ROWS, INPUT_COLUMNS, OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(INPUT_COLUMNS, OUTPUT_COLUMNS);
    CompiledRuleset compiled =
        Kisoku.compiler()
            .compile(
                DecisionTableSources.csv(csv),
                CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    int parallelism = Runtime.getRuntime().availableProcessors();
    ExecutorService pool = Executors.newFixedThreadPool(parallelism);
    try (var ruleset =
        (LoadedRulesetImpl) Kisoku.loader().load(compiled, LoadOptions.memoryMap())) {
      ColumnarBulkKernel kernel = ruleset.bulkKernel();
      List<DecisionInput> inputs =
          DecisionTableFixtures.createBulkVariants(INPUT_COUNT, INPUT_COLUMNS);
      InputBatch batch = kernel.encode(inputs);

      // Warm up the JIT, then take a timed pass.
      kernel.evaluate(batch, pool, parallelism);

      long start = System.nanoTime();
      DecisionOutput[] results = kernel.evaluate(batch, pool, parallelism);
      long elapsedNanos = System.nanoTime() - start;

      assertEquals(INPUT_COUNT, results.length);
      double seconds = elapsedNanos / 1_000_000_000.0;
      System.out.printf(
          "Bulk kernel: %,d inputs x %,d rules on %d threads in %.3fs = %,.0f evals/sec%n",
          INPUT_COUNT, TABLE_ROWS, parallelism, seconds, INPUT_COUNT / seconds);
    } finally {
      pool.shutdownNow();
    }
  }
}
