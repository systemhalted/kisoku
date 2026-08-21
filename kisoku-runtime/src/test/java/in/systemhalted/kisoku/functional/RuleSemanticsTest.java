package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rule selection and matching semantics that are independent of the storage layout: which rule wins
 * when several match, what an absent input field means, and how bulk reports variants that match
 * nothing.
 *
 * <p>Each case is asserted on both the indexed and the non-indexed evaluation paths, because the
 * two reach a verdict by different routes (bitmap intersection versus full verification) and must
 * agree.
 */
class RuleSemanticsTest {
  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  // ---------------------------------------------------------------- priority

  /**
   * The highest-priority matching rule must win regardless of where it sits in the source file.
   *
   * <p>Regression: the compiler physically reordered rows into priority order and also stored the
   * source-row permutation, which the loader re-applied as physical row indices. The ordering was
   * therefore applied twice and the <em>lowest</em>-priority matching rule won.
   */
  @Test
  void highestPriorityRuleWinsWhenItAppearsLastInTheFile(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "priority-last.csv",
            "RULE_ID,PRIORITY,REGION,DISCOUNT",
            "RULE_ID,PRIORITY,EQ,SET",
            "LOW,1,APAC,0.01",
            "MID,50,APAC,0.05",
            "HIGH,99,APAC,0.09");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();

    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.PRIORITY, indexed)) {
        DecisionOutput output = ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC")));
        assertEquals("HIGH", output.ruleId(), "indexed=" + indexed);
        assertEquals(
            new BigDecimal("0.09"), output.outputs().get("DISCOUNT"), "indexed=" + indexed);
      }
    }
  }

  /** Ties in priority fall back to source order, so selection stays deterministic. */
  @Test
  void equalPrioritiesResolveInSourceOrder(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "priority-tie.csv",
            "RULE_ID,PRIORITY,REGION,DISCOUNT",
            "RULE_ID,PRIORITY,EQ,SET",
            "FIRST,10,APAC,0.01",
            "SECOND,10,APAC,0.02");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();

    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.AUTO, indexed)) {
        assertEquals(
            "FIRST",
            ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC"))).ruleId(),
            "indexed=" + indexed);
      }
    }
  }

  /** Without a PRIORITY column, the first matching rule in source order wins. */
  @Test
  void firstMatchWinsWithoutPriorityColumn(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "first-match.csv",
            "RULE_ID,REGION,DISCOUNT",
            "RULE_ID,EQ,SET",
            "FIRST,APAC,0.01",
            "SECOND,APAC,0.02");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();

    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.FIRST_MATCH, indexed)) {
        assertEquals(
            "FIRST",
            ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC"))).ruleId(),
            "indexed=" + indexed);
      }
    }
  }

  // ----------------------------------------------------------- absent inputs

  /**
   * An absent input field must not satisfy a condition, whatever the operator.
   *
   * <p>Regression: a missing field coerced to the null/zero code and was then compared as if the
   * caller had supplied it, so an empty input matched cells such as {@code COUNT EQ 0}, {@code
   * ACTIVE EQ FALSE}, {@code AGE GT -5} and {@code REGION NOT IN (APAC,EMEA)}.
   */
  @Test
  void absentInputSatisfiesNoCondition(@TempDir Path dir) throws IOException {
    Schema schema =
        Schema.builder()
            .column("COUNT", ColumnType.INTEGER)
            .column("ACTIVE", ColumnType.BOOLEAN)
            .column("AGE", ColumnType.INTEGER)
            .column("REGION", ColumnType.STRING)
            .column("LABEL", ColumnType.STRING)
            .build();

    // One rule per operator, each with a condition an absent input would previously have satisfied.
    Path csv =
        writeCsv(
            dir,
            "absent.csv",
            "RULE_ID,COUNT,ACTIVE,AGE,REGION,LABEL",
            "RULE_ID,EQ,EQ,GT,NOT IN,SET",
            "EQ_ZERO,0,,,,ZERO",
            "EQ_FALSE,,FALSE,,,INACTIVE",
            "GT_NEGATIVE,,,-5,,ABOVE_MINUS_FIVE",
            "NOT_IN_SET,,,,(APAC,EMEA),ELSEWHERE");

    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.FIRST_MATCH, indexed)) {
        assertThrows(
            EvaluationException.class,
            () -> ruleset.evaluate(DecisionInput.empty()),
            "empty input must match no rule (indexed=" + indexed + ")");

        // Supplying the field still selects the matching rule.
        assertEquals(
            "EQ_ZERO",
            ruleset.evaluate(DecisionInput.of(Map.of("COUNT", 0))).ruleId(),
            "indexed=" + indexed);
        assertEquals(
            "EQ_FALSE",
            ruleset.evaluate(DecisionInput.of(Map.of("ACTIVE", false))).ruleId(),
            "indexed=" + indexed);
        assertEquals(
            "GT_NEGATIVE",
            ruleset.evaluate(DecisionInput.of(Map.of("AGE", 0))).ruleId(),
            "indexed=" + indexed);
        assertEquals(
            "NOT_IN_SET",
            ruleset.evaluate(DecisionInput.of(Map.of("REGION", "LATAM"))).ruleId(),
            "indexed=" + indexed);
      }
    }
  }

  /**
   * A value that was supplied but is unknown to the dictionary is not the same as an absent value:
   * it can still satisfy negative operators.
   */
  @Test
  void suppliedButUnknownValueIsNotTreatedAsAbsent(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir, "unknown.csv", "RULE_ID,REGION,LABEL", "RULE_ID,NE,SET", "NOT_APAC,APAC,OTHER");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("LABEL", ColumnType.STRING)
            .build();

    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.FIRST_MATCH, indexed)) {
        // Present but never seen at compile time: LATAM differs from APAC, so NE holds.
        assertEquals(
            "NOT_APAC",
            ruleset.evaluate(DecisionInput.of(Map.of("REGION", "LATAM"))).ruleId(),
            "indexed=" + indexed);
        // Absent: "unknown" is not evidence that the value differs.
        assertThrows(
            EvaluationException.class,
            () -> ruleset.evaluate(DecisionInput.empty()),
            "indexed=" + indexed);
      }
    }
  }

  /** A blank cell still carries no condition, so it matches even when the input omits the field. */
  @Test
  void blankCellStillMatchesAbsentInput(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "blank.csv",
            "RULE_ID,REGION,LABEL",
            "RULE_ID,EQ,SET",
            "SPECIFIC,APAC,APAC_RULE",
            "CATCH_ALL,,DEFAULT");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("LABEL", ColumnType.STRING)
            .build();

    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.FIRST_MATCH, indexed)) {
        assertEquals(
            "CATCH_ALL", ruleset.evaluate(DecisionInput.empty()).ruleId(), "indexed=" + indexed);
        assertEquals(
            "SPECIFIC",
            ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC"))).ruleId(),
            "indexed=" + indexed);
      }
    }
  }

  // -------------------------------------------------------------------- bulk

  /**
   * A variant that matches no rule yields an empty result instead of failing the whole batch.
   *
   * <p>Regression: bulk looped over the throwing single-eval entry point, so one unmatched variant
   * discarded the results of every other variant in the batch.
   */
  @Test
  void unmatchedVariantDoesNotAbortTheBatch(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "bulk.csv",
            "RULE_ID,REGION,DISCOUNT",
            "RULE_ID,EQ,SET",
            "APAC_RULE,APAC,0.05",
            "EMEA_RULE,EMEA,0.10");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();

    List<DecisionInput> variants =
        List.of(
            DecisionInput.of(Map.of("REGION", "APAC")),
            DecisionInput.of(Map.of("REGION", "LATAM")), // matches nothing
            DecisionInput.of(Map.of("REGION", "EMEA")));

    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.FIRST_MATCH, indexed)) {
        BulkResult result = ruleset.evaluateBulk(DecisionInput.empty(), variants);

        assertEquals(3, result.size(), "indexed=" + indexed);
        assertEquals(2, result.matchedCount(), "indexed=" + indexed);
        assertEquals(List.of(1), result.unmatchedIndices(), "indexed=" + indexed);

        assertTrue(result.matched(0), "indexed=" + indexed);
        assertEquals("APAC_RULE", result.result(0).orElseThrow().ruleId(), "indexed=" + indexed);

        assertFalse(result.matched(1), "indexed=" + indexed);
        assertTrue(result.result(1).isEmpty(), "indexed=" + indexed);

        assertEquals("EMEA_RULE", result.result(2).orElseThrow().ruleId(), "indexed=" + indexed);
      }
    }
  }

  /**
   * Variant fields override the shared base input, and base fields survive where not overridden.
   */
  @Test
  void variantOverridesBaseInput(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "bulk-overlay.csv",
            "RULE_ID,REGION,TIER,DISCOUNT",
            "RULE_ID,EQ,EQ,SET",
            "APAC_GOLD,APAC,GOLD,0.20",
            "EMEA_GOLD,EMEA,GOLD,0.15");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("TIER", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();

    try (LoadedRuleset ruleset = load(csv, schema, RuleSelectionPolicy.FIRST_MATCH, true)) {
      BulkResult result =
          ruleset.evaluateBulk(
              DecisionInput.of(Map.of("TIER", "GOLD", "REGION", "APAC")),
              List.of(DecisionInput.of(Map.of()), DecisionInput.of(Map.of("REGION", "EMEA"))));

      assertEquals("APAC_GOLD", result.result(0).orElseThrow().ruleId());
      assertEquals("EMEA_GOLD", result.result(1).orElseThrow().ruleId());
    }
  }

  // ----------------------------------------------------------------- helpers

  private LoadedRuleset load(
      Path csv, Schema schema, RuleSelectionPolicy policy, boolean prewarmIndexes) {
    CompiledRuleset compiled =
        compiler.compile(
            DecisionTableSources.csv(csv),
            CompileOptions.production(schema).withRuleSelection(policy));
    return loader.load(compiled, LoadOptions.onHeap().withPrewarmIndexes(prewarmIndexes));
  }

  private static Path writeCsv(Path dir, String name, String... lines) throws IOException {
    Path path = dir.resolve(name);
    Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return path;
  }
}
