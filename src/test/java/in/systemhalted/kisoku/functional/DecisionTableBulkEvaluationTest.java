package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.Kisoku;
import in.systemhalted.kisoku.api.BulkResult;
import in.systemhalted.kisoku.api.CompileOptions;
import in.systemhalted.kisoku.api.CompiledRuleset;
import in.systemhalted.kisoku.api.DecisionInput;
import in.systemhalted.kisoku.api.DecisionOutput;
import in.systemhalted.kisoku.api.LoadOptions;
import in.systemhalted.kisoku.api.LoadedRuleset;
import in.systemhalted.kisoku.api.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.RulesetCompiler;
import in.systemhalted.kisoku.api.RulesetLoader;
import in.systemhalted.kisoku.api.RulesetValidator;
import in.systemhalted.kisoku.api.ValidationResult;
import in.systemhalted.kisoku.io.DecisionTableSources;
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
    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv));
    assertTrue(validation.ok());

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production().withRuleSelection(RuleSelectionPolicy.AUTO));

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      DecisionInput base = DecisionInput.of(Map.of("REGION", "APAC"));
      List<DecisionInput> variants =
          List.of(
              DecisionInput.of(Map.of("AGE", 25)),
              DecisionInput.of(Map.of("AGE", 25, "REGION", "EMEA")),
              DecisionInput.of(Map.of("AGE", 40)));

      BulkResult result = ruleset.evaluateBulk(base, variants);
      assertEquals(3, result.results().size());

      DecisionOutput first = result.results().get(0);
      DecisionOutput second = result.results().get(1);
      DecisionOutput third = result.results().get(2);

      assertEquals("R1", first.ruleId());
      assertEquals("R2", second.ruleId());
      assertEquals("R3", third.ruleId());
    }
  }
}
