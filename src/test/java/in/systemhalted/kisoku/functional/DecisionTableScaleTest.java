package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.systemhalted.kisoku.Kisoku;
import in.systemhalted.kisoku.api.compiler.CompileOptions;
import in.systemhalted.kisoku.api.compiler.CompiledRuleset;
import in.systemhalted.kisoku.api.compiler.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loader.LoadOptions;
import in.systemhalted.kisoku.api.loader.LoadedRuleset;
import in.systemhalted.kisoku.api.loader.RulesetLoader;
import in.systemhalted.kisoku.api.model.Schema;
import in.systemhalted.kisoku.io.DecisionTableSources;
import in.systemhalted.kisoku.testutil.DecisionTableFixtures;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Scale test that generates a typical PRD-sized decision table. */
class DecisionTableScaleTest {
  private static final long TYPICAL_ROWS = 5_000_000L;
  private static final int TYPICAL_INPUT_COLUMNS = 60;
  private static final int TYPICAL_OUTPUT_COLUMNS = 20;

  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  @Tag("scale")
  @Test
  void compilesAndLoadsTypicalScaleTable(@TempDir Path tempDir) throws IOException {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runScaleTests=true to enable large-scale tests.");

    long rows = Long.getLong("kisoku.scaleRows", TYPICAL_ROWS);
    int inputColumns = Integer.getInteger("kisoku.scaleInputs", TYPICAL_INPUT_COLUMNS);
    int outputColumns = Integer.getInteger("kisoku.scaleOutputs", TYPICAL_OUTPUT_COLUMNS);

    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, rows, inputColumns, outputColumns);
    Schema schema = DecisionTableFixtures.largeTableSchema(inputColumns, outputColumns);

    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));
    assertEquals(rows, compiled.metadata().rowCount());
    assertEquals(inputColumns, compiled.metadata().inputColumnCount());
    assertEquals(outputColumns, compiled.metadata().outputColumnCount());

    try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
      assertEquals(rows, ruleset.metadata().rowCount());
      assertEquals(inputColumns, ruleset.metadata().inputColumnCount());
      assertEquals(outputColumns, ruleset.metadata().outputColumnCount());
    }
  }
}
