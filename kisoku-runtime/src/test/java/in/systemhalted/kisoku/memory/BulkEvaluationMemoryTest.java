package in.systemhalted.kisoku.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.testutil.DecisionTableFixtures;
import in.systemhalted.kisoku.testutil.MemoryTestUtils;
import in.systemhalted.kisoku.testutil.MemoryTestUtils.MemorySnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests verifying memory constraints for bulk evaluation.
 *
 * <p>The PRD targets bulk evaluation of 10K variants within 60s and bounded memory. These tests
 * verify that bulk result memory scales with output size, not table size.
 */
class BulkEvaluationMemoryTest {

  private static final int INPUT_COLUMNS = 20;
  private static final int OUTPUT_COLUMNS = 10;

  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  /**
   * Verifies bulk evaluation memory is bounded and proportional to variant count.
   *
   * <p>Memory for BulkResult should scale with the number of variants and output size, not with the
   * table size. This test uses 10K variants as per PRD requirements.
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void bulkEvaluation10KVariantsMemoryIsBounded(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests") || Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runMemoryTests=true or -Dkisoku.runScaleTests=true to enable this test.");

    long rows = 1_000_000L;
    int variantCount = 10_000;

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, INPUT_COLUMNS, OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(INPUT_COLUMNS, OUTPUT_COLUMNS);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      DecisionInput base = DecisionTableFixtures.createBaseInput(INPUT_COLUMNS);
      List<DecisionInput> variants =
          DecisionTableFixtures.createBulkVariants(variantCount, INPUT_COLUMNS);

      MemorySnapshot beforeBulk = MemoryTestUtils.stableSnapshot();
      System.out.printf(
          "Before bulk evaluation (%d variants): %s%n", variantCount, beforeBulk.format());

      BulkResult result = ruleset.evaluateBulk(base, variants);

      MemorySnapshot afterBulk = MemoryTestUtils.stableSnapshot();
      System.out.printf("After bulk evaluation: %s%n", afterBulk.format());

      // Verify result contains all variants
      assertTrue(
          result.results().size() == variantCount,
          String.format("Expected %d results, got %d", variantCount, result.results().size()));

      long heapDelta = afterBulk.heapBytes() - beforeBulk.heapBytes();
      System.out.printf(
          "Heap delta for %d variants: %s%n", variantCount, MemoryTestUtils.formatBytes(heapDelta));

      // Estimate expected memory: ~1KB per result (rule ID + outputs) × 10K = ~10MB
      // Allow 5x overhead for object headers, references, etc.
      long maxExpectedMemory = 50 * 1024 * 1024L; // 50MB

      assertTrue(
          heapDelta < maxExpectedMemory,
          String.format(
              "Bulk evaluation memory %s exceeds expected %s for %d variants",
              MemoryTestUtils.formatBytes(heapDelta),
              MemoryTestUtils.formatBytes(maxExpectedMemory),
              variantCount));
    }
  }

  /**
   * Verifies bulk evaluation memory scales linearly with variant count.
   *
   * <p>Compares memory for 1K vs 10K variants. The ratio should be close to 10x, demonstrating
   * linear scaling with output rather than table size.
   */
  @Tag("memory")
  @Test
  void bulkEvaluationScalesLinearlyWithVariantCount(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests"),
        "Set -Dkisoku.runMemoryTests=true to enable this test.");

    long rows = 100_000L;
    int smallVariantCount = 1_000;
    int largeVariantCount = 10_000;

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, INPUT_COLUMNS, OUTPUT_COLUMNS);
    Schema schema = DecisionTableFixtures.largeTableSchema(INPUT_COLUMNS, OUTPUT_COLUMNS);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    long smallMemory;
    long largeMemory;

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      DecisionInput base = DecisionTableFixtures.createBaseInput(INPUT_COLUMNS);

      // Measure memory for 1K variants
      {
        List<DecisionInput> variants =
            DecisionTableFixtures.createBulkVariants(smallVariantCount, INPUT_COLUMNS);

        MemorySnapshot before = MemoryTestUtils.stableSnapshot();
        BulkResult result = ruleset.evaluateBulk(base, variants);
        MemorySnapshot after = MemoryTestUtils.stableSnapshot();

        smallMemory = Math.max(0, after.heapBytes() - before.heapBytes());
        System.out.printf(
            "%d variants: %s%n", smallVariantCount, MemoryTestUtils.formatBytes(smallMemory));

        // Consume result to prevent optimization
        assertTrue(result.results().size() == smallVariantCount);
      }

      // Force cleanup between measurements
      MemoryTestUtils.forceGcAndStabilize();

      // Measure memory for 10K variants
      {
        List<DecisionInput> variants =
            DecisionTableFixtures.createBulkVariants(largeVariantCount, INPUT_COLUMNS);

        MemorySnapshot before = MemoryTestUtils.stableSnapshot();
        BulkResult result = ruleset.evaluateBulk(base, variants);
        MemorySnapshot after = MemoryTestUtils.stableSnapshot();

        largeMemory = Math.max(0, after.heapBytes() - before.heapBytes());
        System.out.printf(
            "%d variants: %s%n", largeVariantCount, MemoryTestUtils.formatBytes(largeMemory));

        assertTrue(result.results().size() == largeVariantCount);
      }
    }

    // Calculate scaling factor
    double expectedRatio = (double) largeVariantCount / smallVariantCount; // 10x
    double actualRatio = (double) largeMemory / Math.max(smallMemory, 1);

    System.out.printf("Scaling: expected ~%.1fx, actual %.2fx%n", expectedRatio, actualRatio);

    // Allow 50% tolerance for GC timing and allocation variance
    // Ratio should be between 5x and 15x for 10x variant increase
    assertTrue(
        actualRatio >= expectedRatio * 0.5 && actualRatio <= expectedRatio * 1.5,
        String.format(
            "Bulk memory scaling %.2fx is not linear (expected ~%.1fx). 1K: %s, 10K: %s",
            actualRatio,
            expectedRatio,
            MemoryTestUtils.formatBytes(smallMemory),
            MemoryTestUtils.formatBytes(largeMemory)));
  }
}
