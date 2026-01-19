package in.systemhalted.kisoku.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.testutil.DecisionTableFixtures;
import in.systemhalted.kisoku.testutil.MemoryTestUtils;
import in.systemhalted.kisoku.testutil.MemoryTestUtils.MemorySnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests verifying per-evaluation memory constraints.
 *
 * <p>The PRD requires bounded per-evaluation memory that doesn't scale with table size. Each
 * evaluation should allocate only what's needed for input/output, not proportional to the ruleset.
 */
class PerEvaluationMemoryTest {

  private static final int INPUT_COLUMNS = 20;
  private static final int OUTPUT_COLUMNS = 10;

  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  /**
   * Verifies evaluation memory doesn't scale with table size.
   *
   * <p>Compares memory per evaluation between 1K and 1M row tables. The per-evaluation overhead
   * should be similar regardless of table size.
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void evaluationMemoryDoesNotScaleWithTableSize(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests") || Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runMemoryTests=true or -Dkisoku.runScaleTests=true to enable this test.");

    Schema schema = DecisionTableFixtures.largeTableSchema(INPUT_COLUMNS, OUTPUT_COLUMNS);

    // Create small table (1K rows)
    Path smallDir = tempDir.resolve("small");
    Files.createDirectories(smallDir);
    Path smallCsv =
        DecisionTableFixtures.writeLargeTable(smallDir, 1_000L, INPUT_COLUMNS, OUTPUT_COLUMNS);
    CompiledRuleset smallCompiled =
        compiler.compile(
            DecisionTableSources.csv(smallCsv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    // Create large table (1M rows)
    Path largeDir = tempDir.resolve("large");
    Files.createDirectories(largeDir);
    Path largeCsv =
        DecisionTableFixtures.writeLargeTable(largeDir, 1_000_000L, INPUT_COLUMNS, OUTPUT_COLUMNS);
    CompiledRuleset largeCompiled =
        compiler.compile(
            DecisionTableSources.csv(largeCsv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    long smallTableEvalMemory;
    long largeTableEvalMemory;

    // Measure evaluation memory for small table
    try (LoadedRuleset smallRuleset = loader.load(smallCompiled, LoadOptions.memoryMap())) {
      smallTableEvalMemory = measureEvaluationMemory(smallRuleset, INPUT_COLUMNS, 100);
    }

    // Measure evaluation memory for large table
    try (LoadedRuleset largeRuleset = loader.load(largeCompiled, LoadOptions.memoryMap())) {
      largeTableEvalMemory = measureEvaluationMemory(largeRuleset, INPUT_COLUMNS, 100);
    }

    System.out.printf(
        "Per-eval memory - 1K rows: %s, 1M rows: %s%n",
        MemoryTestUtils.formatBytes(smallTableEvalMemory),
        MemoryTestUtils.formatBytes(largeTableEvalMemory));

    // Large table evaluation memory should not be more than 2x small table
    // This allows for some variance but catches linear scaling issues
    double ratio = (double) largeTableEvalMemory / Math.max(smallTableEvalMemory, 1);
    System.out.printf("Memory ratio (large/small): %.2fx%n", ratio);

    assertTrue(
        ratio < 2.0 || largeTableEvalMemory < 10 * 1024 * 1024, // 10MB absolute cap
        String.format(
            "Evaluation memory scales with table size. Small: %s, Large: %s, Ratio: %.2fx",
            MemoryTestUtils.formatBytes(smallTableEvalMemory),
            MemoryTestUtils.formatBytes(largeTableEvalMemory),
            ratio));
  }

  /**
   * Verifies repeated evaluations don't accumulate memory.
   *
   * <p>Running 10K evaluations should not cause unbounded heap growth. The implementation should
   * release evaluation temporaries promptly or reuse buffers.
   */
  @Tag("memory")
  @Test
  void repeatedEvaluationsDoNotAccumulateMemory(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests"),
        "Set -Dkisoku.runMemoryTests=true to enable this test.");

    long rows = 100_000L;
    int evalIterations = 10_000;

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, INPUT_COLUMNS, OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(INPUT_COLUMNS, OUTPUT_COLUMNS);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      // Warmup
      DecisionInput warmupInput = DecisionTableFixtures.createTestInput(0, INPUT_COLUMNS);
      for (int i = 0; i < 100; i++) {
        ruleset.evaluate(warmupInput);
      }

      MemorySnapshot beforeEvals = MemoryTestUtils.stableSnapshot();
      System.out.printf("Before %d evaluations: %s%n", evalIterations, beforeEvals.format());

      // Run many evaluations
      for (int i = 0; i < evalIterations; i++) {
        DecisionInput input = DecisionTableFixtures.createTestInput(i, INPUT_COLUMNS);
        DecisionOutput output = ruleset.evaluate(input);
        // Consume output to prevent dead code elimination
        if (output.ruleId() == null && i == 0) {
          System.out.println("No match found");
        }
      }

      MemorySnapshot afterEvals = MemoryTestUtils.stableSnapshot();
      System.out.printf("After %d evaluations: %s%n", evalIterations, afterEvals.format());

      long heapDelta = afterEvals.heapBytes() - beforeEvals.heapBytes();
      System.out.printf("Heap delta: %s%n", MemoryTestUtils.formatBytes(heapDelta));

      // Heap growth should be bounded (< 100MB for 10K evaluations)
      // This is a soft assertion as GC timing can affect measurements
      long maxAcceptableGrowth = 100 * 1024 * 1024L; // 100MB
      assertTrue(
          heapDelta < maxAcceptableGrowth,
          String.format(
              "Heap grew by %s after %d evaluations, exceeds %s threshold",
              MemoryTestUtils.formatBytes(heapDelta),
              evalIterations,
              MemoryTestUtils.formatBytes(maxAcceptableGrowth)));
    }
  }

  /**
   * Measures average memory per evaluation by running a batch.
   *
   * @param ruleset the loaded ruleset to evaluate
   * @param inputColumns number of input columns
   * @param iterations number of evaluations to run
   * @return estimated bytes per evaluation
   */
  private long measureEvaluationMemory(LoadedRuleset ruleset, int inputColumns, int iterations) {
    // Warmup to ensure JIT compilation and stable state
    DecisionInput warmupInput = DecisionTableFixtures.createTestInput(0, inputColumns);
    for (int i = 0; i < 50; i++) {
      ruleset.evaluate(warmupInput);
    }

    MemorySnapshot before = MemoryTestUtils.stableSnapshot();

    for (int i = 0; i < iterations; i++) {
      DecisionInput input = DecisionTableFixtures.createTestInput(i, inputColumns);
      DecisionOutput output = ruleset.evaluate(input);
      // Consume output to prevent dead code elimination
      if (output.ruleId() == null && i == 0) {
        System.out.println("No match");
      }
    }

    MemorySnapshot after = MemoryTestUtils.stableSnapshot();
    long totalDelta = Math.max(0, after.heapBytes() - before.heapBytes());

    return totalDelta / Math.max(iterations, 1);
  }
}
