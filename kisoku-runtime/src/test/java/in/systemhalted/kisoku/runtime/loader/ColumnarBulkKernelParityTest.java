package in.systemhalted.kisoku.runtime.loader;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bulk kernel's correctness contract: for every input, the columnar bulk kernel must return
 * exactly what single-eval ({@link LoadedRuleset#evaluate}) returns. Single-eval is the oracle.
 */
class ColumnarBulkKernelParityTest {

  /**
   * A table mixing every operator family plus a blank-cell fallback rule, so every input matches.
   */
  private LoadedRulesetImpl loadMixedTable(Path dir) throws IOException {
    Path csv = dir.resolve("mixed.csv");
    try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
      w.write("RULE_ID,PRIORITY,REGION,AGE,SCORE,TIER,DISCOUNT\n");
      w.write("RULE_ID,PRIORITY,IN,BETWEEN,GT,NOT IN,SET\n");
      w.write("R1,50,(APAC,EMEA),(18,65),700,(BLOCKED),0.20\n");
      w.write("R2,40,(US),(21,70),500,(BANNED),0.15\n");
      w.write("R3,30,,(0,40),,,0.10\n"); // only AGE constrained
      w.write("R4,20,(APAC),,300,,0.08\n"); // REGION + SCORE
      w.write("R5,10,,,,,0.05\n"); // fallback: matches anything
    }
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("AGE", ColumnType.INTEGER)
            .column("SCORE", ColumnType.INTEGER)
            .column("TIER", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();
    CompiledRuleset compiled =
        Kisoku.compiler().compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));
    return (LoadedRulesetImpl) Kisoku.loader().load(compiled, LoadOptions.memoryMap());
  }

  private List<DecisionInput> mixedInputs() {
    List<DecisionInput> inputs = new ArrayList<>();
    inputs.add(DecisionInput.of(Map.of("REGION", "APAC", "AGE", 40, "SCORE", 800, "TIER", "GOLD")));
    inputs.add(DecisionInput.of(Map.of("REGION", "EMEA", "AGE", 25, "SCORE", 750, "TIER", "OK")));
    inputs.add(DecisionInput.of(Map.of("REGION", "US", "AGE", 50, "SCORE", 600, "TIER", "OK")));
    inputs.add(DecisionInput.of(Map.of("REGION", "LATAM", "AGE", 30, "SCORE", 100, "TIER", "X")));
    inputs.add(DecisionInput.of(Map.of("REGION", "APAC", "AGE", 80, "SCORE", 350, "TIER", "Y")));
    inputs.add(DecisionInput.of(Map.of("REGION", "ZZ", "AGE", 99, "SCORE", 10, "TIER", "Z")));
    inputs.add(
        DecisionInput.of(Map.of("REGION", "APAC", "AGE", 40, "SCORE", 700, "TIER", "BLOCKED")));
    return inputs;
  }

  private void assertParity(LoadedRulesetImpl ruleset, List<DecisionInput> inputs) {
    ColumnarBulkKernel base = ruleset.bulkKernel();
    // Threshold-independence: default, 0 (force full intersection), 1 (force earliest break) must
    // all agree with single-eval — proving the pruning heuristic is a pure optimization.
    for (ColumnarBulkKernel kernel :
        List.of(base, base.withStopThreshold(0), base.withStopThreshold(1))) {
      DecisionOutput[] bulk = kernel.evaluate(kernel.encode(inputs));
      assertEquals(inputs.size(), bulk.length);
      for (int i = 0; i < inputs.size(); i++) {
        DecisionOutput single = ruleset.evaluate(inputs.get(i));
        assertNotNull(bulk[i], "bulk produced no match for input " + i);
        assertEquals(single.ruleId(), bulk[i].ruleId(), "ruleId mismatch at " + i);
        assertEquals(single.outputs(), bulk[i].outputs(), "outputs mismatch at " + i);
      }
    }
  }

  @Test
  void bulkMatchesSingleEvalAcrossOperators(@TempDir Path tempDir) throws IOException {
    try (LoadedRulesetImpl ruleset = loadMixedTable(tempDir)) {
      assertParity(ruleset, mixedInputs());
    }
  }

  @Test
  void parityHoldsWhenIndexesDisabled(@TempDir Path tempDir) throws IOException {
    // prewarmIndexes(false) exercises the kernel's linear (no-index) path.
    Path csv = tempDir.resolve("mixed.csv");
    try (LoadedRulesetImpl indexed = loadMixedTable(tempDir)) {
      CompiledRuleset compiled =
          Kisoku.compiler()
              .compile(
                  DecisionTableSources.csv(csv),
                  CompileOptions.production(
                      Schema.builder()
                          .column("REGION", ColumnType.STRING)
                          .column("AGE", ColumnType.INTEGER)
                          .column("SCORE", ColumnType.INTEGER)
                          .column("TIER", ColumnType.STRING)
                          .column("DISCOUNT", ColumnType.DECIMAL)
                          .build()));
      try (LoadedRulesetImpl linear =
          (LoadedRulesetImpl)
              Kisoku.loader().load(compiled, LoadOptions.memoryMap().withPrewarmIndexes(false))) {
        assertParity(linear, mixedInputs());
      }
    }
  }

  @Test
  void parallelEvaluationMatchesSequential(@TempDir Path tempDir) throws Exception {
    try (LoadedRulesetImpl ruleset = loadMixedTable(tempDir)) {
      ColumnarBulkKernel kernel = ruleset.bulkKernel();
      // Repeat the inputs so there are enough rows to partition across threads.
      List<DecisionInput> inputs = new ArrayList<>();
      for (int i = 0; i < 500; i++) {
        inputs.addAll(mixedInputs());
      }
      InputBatch batch = kernel.encode(inputs);
      DecisionOutput[] sequential = kernel.evaluate(batch);

      ExecutorService pool = Executors.newFixedThreadPool(8);
      try {
        DecisionOutput[] parallel = kernel.evaluate(batch, pool, 8);
        assertEquals(sequential.length, parallel.length);
        for (int i = 0; i < sequential.length; i++) {
          assertEquals(sequential[i].ruleId(), parallel[i].ruleId(), "ruleId mismatch at " + i);
          assertEquals(sequential[i].outputs(), parallel[i].outputs(), "outputs mismatch at " + i);
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void unmatchedRowsYieldNull(@TempDir Path tempDir) throws IOException {
    // Table with NO fallback row: some inputs match nothing.
    Path csv = tempDir.resolve("no-fallback.csv");
    try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
      w.write("RULE_ID,REGION,DISCOUNT\n");
      w.write("RULE_ID,EQ,SET\n");
      w.write("R1,USA,0.10\n");
      w.write("R2,UK,0.15\n");
    }
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();
    CompiledRuleset compiled =
        Kisoku.compiler().compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));

    try (LoadedRulesetImpl ruleset =
        (LoadedRulesetImpl) Kisoku.loader().load(compiled, LoadOptions.memoryMap())) {
      ColumnarBulkKernel kernel = ruleset.bulkKernel();
      List<DecisionInput> inputs =
          List.of(
              DecisionInput.of(Map.of("REGION", "USA")),
              DecisionInput.of(Map.of("REGION", "FRANCE")), // no match
              DecisionInput.of(Map.of("REGION", "UK")));
      DecisionOutput[] bulk = kernel.evaluate(kernel.encode(inputs));

      assertEquals("R1", bulk[0].ruleId());
      assertNull(bulk[1], "unmatched row should be null");
      assertEquals("R2", bulk[2].ruleId());

      // Single-eval throws for the unmatched input — confirms the same no-match condition.
      assertThrows(
          EvaluationException.class,
          () -> ruleset.evaluate(DecisionInput.of(Map.of("REGION", "FRANCE"))));
    }
  }
}
