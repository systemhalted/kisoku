package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Functional tests for lifecycle behavior and rule selection. */
class DecisionTableLifecycleTest {
  private final RulesetValidator validator = Kisoku.validator();
  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  @Test
  void selectsHighestPriorityWhenPresent(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writePriorityTable(tempDir);
    Schema schema = DecisionTableFixtures.priorityTableSchema();
    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      DecisionInput input = DecisionInput.of(Map.of("AGE", 25, "REGION", "EMEA"));
      DecisionOutput output = ruleset.evaluate(input);
      assertEquals("R2", output.ruleId());
      assertEquals("0.10", output.outputs().get("DISCOUNT"));
    }
  }

  @Test
  void fallsBackToFirstMatchWhenPriorityMissing(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writeFirstMatchTable(tempDir);
    Schema schema = DecisionTableFixtures.firstMatchTableSchema();
    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.onHeap())) {
      DecisionInput input = DecisionInput.of(Map.of("AGE", 25, "REGION", "APAC"));
      DecisionOutput output = ruleset.evaluate(input);
      assertEquals("R1", output.ruleId());
      assertEquals("0.05", output.outputs().get("DISCOUNT"));
    }
  }

  @Test
  void allowsExplicitFirstMatchOverride(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writePriorityTable(tempDir);
    Schema schema = DecisionTableFixtures.priorityTableSchema();

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.FIRST_MATCH));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.onHeap())) {
      DecisionInput input = DecisionInput.of(Map.of("AGE", 25, "REGION", "APAC"));
      DecisionOutput output = ruleset.evaluate(input);
      assertEquals("R1", output.ruleId());
      assertEquals("0.05", output.outputs().get("DISCOUNT"));
    }
  }

  @Test
  void includesTestColumnsInAllArtifacts(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writePriorityTable(tempDir);
    Schema schema = DecisionTableFixtures.priorityTableSchema();

    CompiledRuleset testArtifact =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.testInclusive(schema).withRuleSelection(RuleSelectionPolicy.AUTO));
    assertTrue(testArtifact.metadata().outputColumns().contains("TEST_EXPECTED_SEGMENT"));

    // TEST_ columns are included in production artifacts (flagged for evaluation-time control)
    CompiledRuleset prodArtifact =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));
    assertTrue(prodArtifact.metadata().outputColumns().contains("TEST_EXPECTED_SEGMENT"));
  }
}
