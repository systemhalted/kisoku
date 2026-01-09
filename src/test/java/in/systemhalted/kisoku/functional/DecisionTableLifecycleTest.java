package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.Kisoku;
import in.systemhalted.kisoku.api.compiler.CompileOptions;
import in.systemhalted.kisoku.api.compiler.CompiledRuleset;
import in.systemhalted.kisoku.api.compiler.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loader.LoadOptions;
import in.systemhalted.kisoku.api.loader.LoadedRuleset;
import in.systemhalted.kisoku.api.loader.RulesetLoader;
import in.systemhalted.kisoku.api.validator.RulesetValidator;
import in.systemhalted.kisoku.api.validator.ValidationResult;
import in.systemhalted.kisoku.io.DecisionTableSources;
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
    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv));
    assertTrue(validation.ok());

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production().withRuleSelection(RuleSelectionPolicy.AUTO));

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
    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv));
    assertTrue(validation.ok());

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production().withRuleSelection(RuleSelectionPolicy.AUTO));

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

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production().withRuleSelection(RuleSelectionPolicy.FIRST_MATCH));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.onHeap())) {
      DecisionInput input = DecisionInput.of(Map.of("AGE", 25, "REGION", "APAC"));
      DecisionOutput output = ruleset.evaluate(input);
      assertEquals("R1", output.ruleId());
      assertEquals("0.05", output.outputs().get("DISCOUNT"));
    }
  }

  @Test
  void stripsTestOnlyColumnsInProductionArtifacts(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writePriorityTable(tempDir);

    CompiledRuleset testArtifact =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.testInclusive().withRuleSelection(RuleSelectionPolicy.AUTO));
    assertTrue(testArtifact.metadata().outputColumns().contains("TEST_EXPECTED_SEGMENT"));

    CompiledRuleset prodArtifact =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production().withRuleSelection(RuleSelectionPolicy.AUTO));
    assertFalse(prodArtifact.metadata().outputColumns().contains("TEST_EXPECTED_SEGMENT"));
  }
}
