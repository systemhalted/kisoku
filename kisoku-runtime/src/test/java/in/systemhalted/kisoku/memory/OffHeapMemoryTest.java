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
    Assumptions.assumeTrue(
        MemoryTestUtils.directBufferPool().isPresent(),
        "Direct buffer pool MXBean not available on this JVM");

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
   * Verifies that the table's column data stays off-heap when memory-mapped, by comparing against
   * an on-heap load of the same artifact file.
   *
   * <p>Both loads use {@code prewarmIndexes(false)} so the measurement isolates the column data
   * from the optional, heap-resident bitmap indexes. The on-heap load reads the whole artifact onto
   * the heap, so its heap growth is at least the artifact size; the memory-mapped load reads column
   * data lazily through the mapping, so it keeps almost none of the artifact on the heap. The
   * difference between the two heap deltas — measured identically in the same run — is the artifact
   * NOT being on the heap under mapping. (A mapped buffer lives in the JVM's "mapped" pool, not the
   * "direct" pool, so it shows up as neither heap nor direct usage.)
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void mappedLoadKeepsArtifactOffHeapVersusOnHeap(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests") || Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runMemoryTests=true or -Dkisoku.runScaleTests=true to enable this test.");

    long rows = 500_000L;
    int inputColumns = TEST_INPUT_COLUMNS;
    int outputColumns = TEST_OUTPUT_COLUMNS;

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, inputColumns, outputColumns);
    Schema schema = DecisionTableFixtures.largeTableSchema(inputColumns, outputColumns);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    Path artifact = tempDir.resolve("ruleset.kss");
    compiled.writeTo(artifact);
    long artifactSize = java.nio.file.Files.size(artifact);

    long mappedHeapDelta =
        heapDeltaForLoad(artifact, LoadOptions.memoryMap().withPrewarmIndexes(false));
    long onHeapDelta = heapDeltaForLoad(artifact, LoadOptions.onHeap().withPrewarmIndexes(false));

    System.out.printf(
        "Artifact: %s | onHeap heap delta: %s | mapped heap delta: %s%n",
        MemoryTestUtils.formatBytes(artifactSize),
        MemoryTestUtils.formatBytes(onHeapDelta),
        MemoryTestUtils.formatBytes(mappedHeapDelta));

    // The on-heap load must hold most of the artifact on the heap.
    assertTrue(
        onHeapDelta > artifactSize / 2,
        String.format(
            "Expected on-heap load to retain most of the %s artifact on heap, but heap grew only %s",
            MemoryTestUtils.formatBytes(artifactSize), MemoryTestUtils.formatBytes(onHeapDelta)));

    // The mapped load must avoid putting the artifact on the heap: it should save at least half the
    // artifact size of heap relative to the on-heap load.
    assertTrue(
        onHeapDelta - mappedHeapDelta > artifactSize / 2,
        String.format(
            "Memory-mapped load did not keep the artifact off-heap. onHeap delta: %s, mapped delta: "
                + "%s, artifact: %s",
            MemoryTestUtils.formatBytes(onHeapDelta),
            MemoryTestUtils.formatBytes(mappedHeapDelta),
            MemoryTestUtils.formatBytes(artifactSize)));
  }

  /** Loads the artifact with the given options and returns the stabilized heap growth in bytes. */
  private long heapDeltaForLoad(Path artifact, LoadOptions options) throws IOException {
    MemorySnapshot before = MemoryTestUtils.stableSnapshot();
    try (LoadedRuleset ruleset = loader.load(artifact, options)) {
      MemorySnapshot after = MemoryTestUtils.stableSnapshot();
      return after.heapBytes() - before.heapBytes();
    }
  }
}
