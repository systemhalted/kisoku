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
 * Tests verifying off-heap memory behavior with different load modes.
 *
 * <p>Validates that memoryMap() mode uses direct buffers while onHeap() mode uses heap memory.
 */
class OffHeapMemoryTest {

  private static final long TEST_ROWS = 100_000L;
  private static final int TEST_INPUT_COLUMNS = 20;
  private static final int TEST_OUTPUT_COLUMNS = 10;

  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  /**
   * Verifies that memoryMap() mode allocates direct buffers.
   *
   * <p>Memory-mapped loading should increase direct buffer count/size, indicating off-heap storage
   * is being used.
   */
  @Tag("memory")
  @Test
  void memoryMapUsesDirectBuffers(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests"),
        "Set -Dkisoku.runMemoryTests=true to enable this test.");

    Path csv =
        DecisionTableFixtures.writeLargeTable(
            tempDir, TEST_ROWS, TEST_INPUT_COLUMNS, TEST_OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(TEST_INPUT_COLUMNS, TEST_OUTPUT_COLUMNS);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    MemorySnapshot beforeLoad = MemoryTestUtils.stableSnapshot();
    System.out.printf("Before memoryMap load: %s%n", beforeLoad.format());

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      MemorySnapshot afterLoad = MemoryTestUtils.stableSnapshot();
      System.out.printf("After memoryMap load: %s%n", afterLoad.format());

      long directDelta = afterLoad.directBytes() - beforeLoad.directBytes();
      System.out.printf("Direct buffer delta: %s%n", MemoryTestUtils.formatBytes(directDelta));

      assertTrue(
          afterLoad.directCount() > beforeLoad.directCount()
              || afterLoad.directBytes() > beforeLoad.directBytes(),
          String.format(
              "memoryMap() should allocate direct buffers. Before: %d buffers, After: %d buffers",
              beforeLoad.directCount(), afterLoad.directCount()));
    }
  }

  /**
   * Verifies that onHeap() mode uses heap memory, not direct buffers.
   *
   * <p>On-heap loading should increase heap usage significantly while direct buffer usage should
   * remain relatively stable.
   */
  @Tag("memory")
  @Test
  void onHeapDoesNotUseDirectBuffers(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests"),
        "Set -Dkisoku.runMemoryTests=true to enable this test.");

    Path csv =
        DecisionTableFixtures.writeLargeTable(
            tempDir, TEST_ROWS, TEST_INPUT_COLUMNS, TEST_OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(TEST_INPUT_COLUMNS, TEST_OUTPUT_COLUMNS);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    MemorySnapshot beforeLoad = MemoryTestUtils.stableSnapshot();
    System.out.printf("Before onHeap load: %s%n", beforeLoad.format());

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.onHeap())) {
      MemorySnapshot afterLoad = MemoryTestUtils.stableSnapshot();
      System.out.printf("After onHeap load: %s%n", afterLoad.format());

      long heapDelta = afterLoad.heapBytes() - beforeLoad.heapBytes();
      long directDelta = afterLoad.directBytes() - beforeLoad.directBytes();

      System.out.printf(
          "Heap delta: %s, Direct delta: %s%n",
          MemoryTestUtils.formatBytes(heapDelta), MemoryTestUtils.formatBytes(directDelta));

      // Heap should grow more than direct memory for onHeap mode
      assertTrue(
          heapDelta > directDelta,
          String.format(
              "onHeap() should use heap memory. Heap delta: %s, Direct delta: %s",
              MemoryTestUtils.formatBytes(heapDelta), MemoryTestUtils.formatBytes(directDelta)));
    }
  }

  /**
   * Verifies heap growth is minimal when using memory-mapped loading.
   *
   * <p>With off-heap storage, heap growth should be much smaller than the actual data size. This
   * allows handling large tables without heap pressure.
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void heapGrowthMinimalWithMemoryMap(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests") || Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runMemoryTests=true or -Dkisoku.runScaleTests=true to enable this test.");

    long rows = 500_000L;
    int inputColumns = TEST_INPUT_COLUMNS;
    int outputColumns = TEST_OUTPUT_COLUMNS;

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, inputColumns, outputColumns);
    Schema schema = DecisionTableFixtures.largeTableSchema(inputColumns, outputColumns);

    // Estimate raw data size (rough approximation)
    long estimatedDataSize = rows * (inputColumns + outputColumns) * 10; // ~10 bytes per cell

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    MemorySnapshot beforeLoad = MemoryTestUtils.stableSnapshot();

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      MemorySnapshot afterLoad = MemoryTestUtils.stableSnapshot();

      long heapDelta = afterLoad.heapBytes() - beforeLoad.heapBytes();
      double heapRatio = (double) heapDelta / estimatedDataSize;

      System.out.printf(
          "Heap delta: %s, Estimated data size: %s, Ratio: %.2f%%%n",
          MemoryTestUtils.formatBytes(heapDelta),
          MemoryTestUtils.formatBytes(estimatedDataSize),
          heapRatio * 100);

      // Heap growth should be less than 20% of data size for memory-mapped mode
      // This threshold allows for metadata, indexes, and JVM overhead
      assertTrue(
          heapRatio < 0.20,
          String.format(
              "Heap growth ratio %.2f%% exceeds 20%% threshold for memory-mapped mode. "
                  + "Heap delta: %s, Data size: %s",
              heapRatio * 100,
              MemoryTestUtils.formatBytes(heapDelta),
              MemoryTestUtils.formatBytes(estimatedDataSize)));
    }
  }
}
