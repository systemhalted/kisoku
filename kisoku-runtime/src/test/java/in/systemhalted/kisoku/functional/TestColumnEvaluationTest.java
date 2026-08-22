package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PRD FR2: test columns are always in the artifact (flag 0x02) and excluded from evaluation by
 * default; {@code LoadOptions.withIncludeTestColumns(true)} opts them in, so a table's embedded
 * test expectations can run against real inputs.
 */
class TestColumnEvaluationTest {

  private static final Schema SCHEMA =
      Schema.builder()
          .column("REGION", ColumnType.STRING)
          .column("TEST_CHANNEL", ColumnType.STRING)
          .column("DISCOUNT", ColumnType.DECIMAL)
          .column("TEST_EXPECTED_SEGMENT", ColumnType.STRING)
          .build();

  private CompiledRuleset compile(Path dir) throws IOException {
    Path csv = dir.resolve("test-cols.csv");
    Files.writeString(
        csv,
        String.join(
                "\n",
                "RULE_ID,REGION,TEST_CHANNEL,DISCOUNT,TEST_EXPECTED_SEGMENT",
                "RULE_ID,EQ,EQ,SET,SET",
                "APAC_WEB,APAC,WEB,0.10,SEG_A",
                "APAC_ANY,APAC,,0.05,SEG_B")
            + "\n",
        StandardCharsets.UTF_8);
    return Kisoku.compiler()
        .compile(DecisionTableSources.csv(csv), CompileOptions.production(SCHEMA));
  }

  @Test
  void testColumnsAreExcludedByDefault(@TempDir Path dir) throws IOException {
    try (LoadedRuleset ruleset = Kisoku.loader().load(compile(dir), LoadOptions.memoryMap())) {
      // TEST_CHANNEL is ignored, so the first APAC rule wins even without a channel input,
      // and the TEST_ output never surfaces.
      DecisionOutput output = ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC")));
      assertEquals("APAC_WEB", output.ruleId());
      assertFalse(output.outputs().containsKey("TEST_EXPECTED_SEGMENT"));
    }
  }

  @Test
  void includedTestColumnsMatchAndSurfaceOutputs(@TempDir Path dir) throws IOException {
    try (LoadedRuleset ruleset =
        Kisoku.loader().load(compile(dir), LoadOptions.memoryMap().withIncludeTestColumns(true))) {
      // With test columns active, TEST_CHANNEL is a real condition...
      DecisionOutput web =
          ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC", "TEST_CHANNEL", "WEB")));
      assertEquals("APAC_WEB", web.ruleId());
      assertEquals("SEG_A", web.outputs().get("TEST_EXPECTED_SEGMENT"));

      // ...an absent TEST_CHANNEL fails the constrained rule and falls to the blank-cell rule...
      DecisionOutput any = ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC")));
      assertEquals("APAC_ANY", any.ruleId());
      assertEquals("SEG_B", any.outputs().get("TEST_EXPECTED_SEGMENT"));

      // ...and a non-matching channel matches only the unconstrained rule.
      DecisionOutput mobile =
          ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC", "TEST_CHANNEL", "MOBILE")));
      assertEquals("APAC_ANY", mobile.ruleId());

      assertThrows(
          EvaluationException.class,
          () -> ruleset.evaluate(DecisionInput.of(Map.of("REGION", "LATAM"))));
    }
  }
}
