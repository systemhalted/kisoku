package in.systemhalted.kisoku.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.testutil.DecisionTableFixtures;
import in.systemhalted.kisoku.testutil.MemoryTestUtils;
import in.systemhalted.kisoku.testutil.MemoryTestUtils.MemorySnapshot;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Memory tests verifying the PRD heap budget constraint.
 *
 * <p>The PRD requires JVM heap to stay under 1GB for a typical workload of 5M rows × 80 columns.
 * These tests verify that constraint is met when using memory-mapped loading.
 */
class HeapBudgetMemoryTest {

  private static final long ONE_GB = 1024L * 1024 * 1024;
  private static final long TYPICAL_ROWS = 5_000_000L;
  private static final long MAX_SCALE_ROWS = 20_000_000L;
  private static final int TYPICAL_INPUT_COLUMNS = 60;
  private static final int TYPICAL_OUTPUT_COLUMNS = 20;

  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  /**
   * Verifies heap stays under 1GB when loading a 5M row table with memory-mapped mode.
   *
   * <p>This is the core PRD constraint: typical workload (5M rows × 80 columns) must fit within 1GB
   * heap budget when using off-heap storage.
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void heapStaysUnder1GBDuringMemoryMappedLoad(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests") || Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runMemoryTests=true or -Dkisoku.runScaleTests=true to enable this test.");

    long rows = Long.getLong("kisoku.scaleRows", TYPICAL_ROWS);
    int inputColumns = Integer.getInteger("kisoku.scaleInputs", TYPICAL_INPUT_COLUMNS);
    int outputColumns = Integer.getInteger("kisoku.scaleOutputs", TYPICAL_OUTPUT_COLUMNS);

    MemorySnapshot baseline = MemoryTestUtils.stableSnapshot();
    System.out.printf("Baseline memory: %s%n", baseline.format());

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, inputColumns, outputColumns);
    Schema schema = DecisionTableFixtures.largeTableSchema(inputColumns, outputColumns);

    MemorySnapshot afterGeneration = MemoryTestUtils.stableSnapshot();
    System.out.printf("After CSV generation: %s%n", afterGeneration.format());

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    MemorySnapshot afterCompile = MemoryTestUtils.stableSnapshot();
    System.out.printf("After compilation: %s%n", afterCompile.format());

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      // Release compiled reference before measurement to avoid inflating heap metrics
      compiled = null;
      MemorySnapshot afterLoad = MemoryTestUtils.stableSnapshot();
      System.out.printf("After load (memory-mapped): %s%n", afterLoad.format());

      long heapDelta = afterLoad.heapBytes() - baseline.heapBytes();
      System.out.printf(
          "Heap delta from baseline: %s (budget: %s)%n",
          MemoryTestUtils.formatBytes(heapDelta), MemoryTestUtils.formatBytes(ONE_GB));

      assertTrue(
          afterLoad.heapBytes() < ONE_GB,
          String.format(
              "Heap usage %s exceeds 1GB budget. Delta from baseline: %s",
              MemoryTestUtils.formatBytes(afterLoad.heapBytes()),
              MemoryTestUtils.formatBytes(heapDelta)));

      // Verify direct buffers are being used (memory-mapped mode)
      assertTrue(
          afterLoad.directBytes() > afterGeneration.directBytes(),
          "Memory-mapped load should allocate direct buffers");
    }
  }

  /**
   * Maximum scale test: verifies 20M rows can be loaded within heap budget.
   *
   * <p>This test requires explicit opt-in via -Dkisoku.runMaxScaleTests=true as it needs
   * significant time and disk space.
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void heapStaysUnder1GBFor20MRows(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMaxScaleTests"),
        "Set -Dkisoku.runMaxScaleTests=true to enable 20M row test.");

    long rows = Long.getLong("kisoku.scaleRows", MAX_SCALE_ROWS);
    int inputColumns = Integer.getInteger("kisoku.scaleInputs", TYPICAL_INPUT_COLUMNS);
    int outputColumns = Integer.getInteger("kisoku.scaleOutputs", TYPICAL_OUTPUT_COLUMNS);

    MemorySnapshot baseline = MemoryTestUtils.stableSnapshot();
    System.out.printf("[20M] Baseline memory: %s%n", baseline.format());

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, inputColumns, outputColumns);
    Schema schema = DecisionTableFixtures.largeTableSchema(inputColumns, outputColumns);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      // Release compiled reference before measurement to avoid inflating heap metrics
      compiled = null;
      MemorySnapshot afterLoad = MemoryTestUtils.stableSnapshot();
      System.out.printf("[20M] After load (memory-mapped): %s%n", afterLoad.format());

      long heapDelta = afterLoad.heapBytes() - baseline.heapBytes();
      System.out.printf(
          "[20M] Heap delta from baseline: %s (budget: %s)%n",
          MemoryTestUtils.formatBytes(heapDelta), MemoryTestUtils.formatBytes(ONE_GB));

      assertTrue(
          afterLoad.heapBytes() < ONE_GB,
          String.format(
              "[20M] Heap usage %s exceeds 1GB budget. Delta from baseline: %s",
              MemoryTestUtils.formatBytes(afterLoad.heapBytes()),
              MemoryTestUtils.formatBytes(heapDelta)));
    }
  }
}
