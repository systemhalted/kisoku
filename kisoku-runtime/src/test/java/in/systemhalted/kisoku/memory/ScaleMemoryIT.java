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
import in.systemhalted.kisoku.api.validation.RulesetValidator;
import in.systemhalted.kisoku.api.validation.ValidationResult;
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
 * Integration tests tracking memory through the full lifecycle at scale.
 *
 * <p>These tests verify memory constraints at each stage: generate → validate → compile → load →
 * evaluate. They require significant time and resources, so are gated by system properties.
 */
class ScaleMemoryIT {

  private static final long ONE_GB = 1024L * 1024 * 1024;
  private static final long TYPICAL_ROWS = 5_000_000L;
  private static final long MAX_SCALE_ROWS = 20_000_000L;
  private static final int TYPICAL_INPUT_COLUMNS = 60;
  private static final int TYPICAL_OUTPUT_COLUMNS = 20;

  private final RulesetValidator validator = Kisoku.validator();
  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  /**
   * Tracks memory through full lifecycle at 5M rows.
   *
   * <p>Measures heap and direct buffer usage at each stage to understand memory profile and verify
   * PRD constraints are met.
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void fullLifecycleMemoryAt5MRows(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runScaleTests=true to enable this test.");

    long rows = Long.getLong("kisoku.scaleRows", TYPICAL_ROWS);
    int inputColumns = Integer.getInteger("kisoku.scaleInputs", TYPICAL_INPUT_COLUMNS);
    int outputColumns = Integer.getInteger("kisoku.scaleOutputs", TYPICAL_OUTPUT_COLUMNS);

    System.out.printf(
        "=== Full Lifecycle Memory Test: %,d rows × %d inputs × %d outputs ===%n",
        rows, inputColumns, outputColumns);

    // Stage 0: Baseline
    MemorySnapshot baseline = MemoryTestUtils.stableSnapshot();
    System.out.printf("Stage 0 [Baseline]: %s%n", baseline.format());

    // Stage 1: Generate CSV
    long generateStart = System.currentTimeMillis();
    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, inputColumns, outputColumns);
    long generateTime = System.currentTimeMillis() - generateStart;

    MemorySnapshot afterGenerate = MemoryTestUtils.stableSnapshot();
    System.out.printf(
        "Stage 1 [Generate CSV]: %s (took %,dms)%n", afterGenerate.format(), generateTime);
    printDelta("Generate", afterGenerate, baseline);

    Schema schema = DecisionTableFixtures.largeTableSchema(inputColumns, outputColumns);

    // Stage 2: Validate
    long validateStart = System.currentTimeMillis();
    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    long validateTime = System.currentTimeMillis() - validateStart;

    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    MemorySnapshot afterValidate = MemoryTestUtils.stableSnapshot();
    System.out.printf(
        "Stage 2 [Validate]: %s (took %,dms)%n", afterValidate.format(), validateTime);
    printDelta("Validate", afterValidate, afterGenerate);

    // Stage 3: Compile
    long compileStart = System.currentTimeMillis();
    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));
    long compileTime = System.currentTimeMillis() - compileStart;

    MemorySnapshot afterCompile = MemoryTestUtils.stableSnapshot();
    System.out.printf("Stage 3 [Compile]: %s (took %,dms)%n", afterCompile.format(), compileTime);
    printDelta("Compile", afterCompile, afterValidate);

    // Stage 4: Load
    long loadStart = System.currentTimeMillis();
    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      long loadTime = System.currentTimeMillis() - loadStart;

      MemorySnapshot afterLoad = MemoryTestUtils.stableSnapshot();
      System.out.printf("Stage 4 [Load]: %s (took %,dms)%n", afterLoad.format(), loadTime);
      printDelta("Load", afterLoad, afterCompile);

      // Verify heap constraint
      assertTrue(
          afterLoad.heapBytes() < ONE_GB,
          String.format(
              "Heap %s exceeds 1GB after load",
              MemoryTestUtils.formatBytes(afterLoad.heapBytes())));

      // Stage 5: Evaluate (sample evaluations)
      int evalCount = 1000;
      long evalStart = System.currentTimeMillis();

      for (int i = 0; i < evalCount; i++) {
        DecisionInput input = DecisionTableFixtures.createTestInput(i, inputColumns);
        DecisionOutput output = ruleset.evaluate(input);
        // Consume to prevent DCE
        if (output.ruleId() == null && i == 0) {
          System.out.println("No match");
        }
      }

      long evalTime = System.currentTimeMillis() - evalStart;
      MemorySnapshot afterEval = MemoryTestUtils.stableSnapshot();
      System.out.printf(
          "Stage 5 [Evaluate ×%d]: %s (took %,dms, %.2fms/eval)%n",
          evalCount, afterEval.format(), evalTime, (double) evalTime / evalCount);
      printDelta("Evaluate", afterEval, afterLoad);

      // Final heap check
      assertTrue(
          afterEval.heapBytes() < ONE_GB,
          String.format(
              "Heap %s exceeds 1GB after evaluation",
              MemoryTestUtils.formatBytes(afterEval.heapBytes())));

      // Summary
      System.out.println("\n=== Summary ===");
      printDelta("Total from baseline", afterEval, baseline);
      System.out.printf(
          "Peak heap: %s (budget: %s)%n",
          MemoryTestUtils.formatBytes(
              Math.max(
                  Math.max(afterCompile.heapBytes(), afterLoad.heapBytes()),
                  afterEval.heapBytes())),
          MemoryTestUtils.formatBytes(ONE_GB));
    }
  }

  /**
   * Maximum scale test: full lifecycle at 20M rows.
   *
   * <p>Requires explicit opt-in and significant resources (time, disk, memory).
   */
  @Tag("memory")
  @Tag("scale")
  @Test
  void fullLifecycleMemoryAt20MRows(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMaxScaleTests"),
        "Set -Dkisoku.runMaxScaleTests=true to enable 20M row test.");

    long rows = Long.getLong("kisoku.scaleRows", MAX_SCALE_ROWS);
    int inputColumns = Integer.getInteger("kisoku.scaleInputs", TYPICAL_INPUT_COLUMNS);
    int outputColumns = Integer.getInteger("kisoku.scaleOutputs", TYPICAL_OUTPUT_COLUMNS);

    System.out.printf(
        "=== [MAX SCALE] Full Lifecycle Memory Test: %,d rows × %d inputs × %d outputs ===%n",
        rows, inputColumns, outputColumns);

    MemorySnapshot baseline = MemoryTestUtils.stableSnapshot();
    System.out.printf("[20M] Baseline: %s%n", baseline.format());

    // Generate
    System.out.println("[20M] Generating CSV (this may take several minutes)...");
    long generateStart = System.currentTimeMillis();
    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, inputColumns, outputColumns);
    long generateTime = System.currentTimeMillis() - generateStart;
    System.out.printf("[20M] CSV generation took %,dms%n", generateTime);

    Schema schema = DecisionTableFixtures.largeTableSchema(inputColumns, outputColumns);

    // Compile (skip validation for speed at max scale)
    System.out.println("[20M] Compiling...");
    long compileStart = System.currentTimeMillis();
    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));
    long compileTime = System.currentTimeMillis() - compileStart;

    MemorySnapshot afterCompile = MemoryTestUtils.stableSnapshot();
    System.out.printf("[20M] Compile: %s (took %,dms)%n", afterCompile.format(), compileTime);

    // Load
    System.out.println("[20M] Loading with memory-mapping...");
    long loadStart = System.currentTimeMillis();
    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      long loadTime = System.currentTimeMillis() - loadStart;

      MemorySnapshot afterLoad = MemoryTestUtils.stableSnapshot();
      System.out.printf("[20M] Load: %s (took %,dms)%n", afterLoad.format(), loadTime);

      // Verify heap constraint
      assertTrue(
          afterLoad.heapBytes() < ONE_GB,
          String.format(
              "[20M] Heap %s exceeds 1GB after load",
              MemoryTestUtils.formatBytes(afterLoad.heapBytes())));

      // Sample evaluations
      int evalCount = 100;
      long evalStart = System.currentTimeMillis();
      for (int i = 0; i < evalCount; i++) {
        DecisionInput input = DecisionTableFixtures.createTestInput(i, inputColumns);
        ruleset.evaluate(input);
      }
      long evalTime = System.currentTimeMillis() - evalStart;
      System.out.printf(
          "[20M] Evaluated %d inputs in %,dms (%.2fms/eval)%n",
          evalCount, evalTime, (double) evalTime / evalCount);

      MemorySnapshot afterEval = MemoryTestUtils.stableSnapshot();
      System.out.printf("[20M] After evaluation: %s%n", afterEval.format());

      assertTrue(
          afterEval.heapBytes() < ONE_GB,
          String.format(
              "[20M] Heap %s exceeds 1GB after evaluation",
              MemoryTestUtils.formatBytes(afterEval.heapBytes())));

      printDelta("[20M] Total from baseline", afterEval, baseline);
    }
  }

  private void printDelta(String label, MemorySnapshot current, MemorySnapshot previous) {
    MemorySnapshot delta = current.deltaFrom(previous);
    System.out.printf("  → %s delta: %s%n", label, delta.format());
  }
}
