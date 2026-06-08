package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.api.validation.RulesetValidator;
import in.systemhalted.kisoku.api.validation.ValidationResult;
import in.systemhalted.kisoku.testutil.DecisionTableFixtures;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Functional tests for bulk evaluation ordering and isolation. */
class DecisionTableBulkEvaluationTest {
  private final RulesetValidator validator = Kisoku.validator();
  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  @Test
  void evaluatesBulkInputsInOrderWithIsolation(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writePriorityTable(tempDir);
    Schema schema = DecisionTableFixtures.priorityTableSchema();
    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      DecisionInput base = DecisionInput.of(Map.of("REGION", "APAC"));
      List<DecisionInput> variants =
          List.of(
              DecisionInput.of(Map.of("AGE", 25)),
              DecisionInput.of(Map.of("AGE", 25, "REGION", "EMEA")),
              DecisionInput.of(Map.of("AGE", 40)));

      BulkResult result = ruleset.evaluateBulk(base, variants);
      assertEquals(3, result.size());

      DecisionOutput first = result.get(0).orElseThrow();
      DecisionOutput second = result.get(1).orElseThrow();
      DecisionOutput third = result.get(2).orElseThrow();

      assertEquals("R1", first.ruleId());
      assertEquals("R2", second.ruleId());
      assertEquals("R3", third.ruleId());
    }
  }

  @Test
  void nonMatchingVariantYieldsEmptyWithoutAbortingBatch(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writePriorityTable(tempDir);
    Schema schema = DecisionTableFixtures.priorityTableSchema();

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      DecisionInput base = DecisionInput.of(Map.of("REGION", "APAC"));
      List<DecisionInput> variants =
          List.of(
              DecisionInput.of(Map.of("AGE", 25)), // matches R1
              DecisionInput.of(Map.of("AGE", -999, "REGION", "NOWHERE")), // matches nothing
              DecisionInput.of(Map.of("AGE", 40))); // matches R3

      BulkResult result = ruleset.evaluateBulk(base, variants);

      assertEquals(3, result.size());
      assertEquals("R1", result.get(0).orElseThrow().ruleId());
      assertTrue(result.get(1).isEmpty(), "middle variant should match no rule");
      assertEquals("R3", result.get(2).orElseThrow().ruleId());
    }
  }
}
