package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.MatchDiagnostics;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import java.io.IOException;
import java.math.BigDecimal;
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
 * The public bulk API (sequential and parallel) and {@code explain} diagnostics - the pieces of the
 * declared API surface completed after the review: bulk runs on the columnar kernel, parallel bulk
 * must equal sequential bulk, and {@code explain} renders the winning rule's conditions.
 */
class PublicBulkAndDiagnosticsTest {

  private static final Schema SCHEMA =
      Schema.builder()
          .column("REGION", ColumnType.STRING)
          .column("AGE", ColumnType.INTEGER)
          .column("SCORE", ColumnType.INTEGER)
          .column("DISCOUNT", ColumnType.DECIMAL)
          .build();

  private LoadedRuleset load(Path dir) throws IOException {
    Path csv = dir.resolve("bulk.csv");
    Files.writeString(
        csv,
        String.join(
                "\n",
                "RULE_ID,PRIORITY,REGION,AGE,SCORE,DISCOUNT",
                "RULE_ID,PRIORITY,IN,BETWEEN,GT,SET",
                "GOLD,1,(APAC,EMEA),(18,65),700,0.20",
                "SILVER,2,(APAC,EMEA),(18,65),,0.10",
                "FALLBACK,9,,,,0.01")
            + "\n",
        StandardCharsets.UTF_8);
    CompiledRuleset compiled =
        Kisoku.compiler().compile(DecisionTableSources.csv(csv), CompileOptions.production(SCHEMA));
    return Kisoku.loader().load(compiled, LoadOptions.memoryMap());
  }

  private static List<DecisionInput> variants(int count) {
    List<DecisionInput> variants = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      variants.add(
          DecisionInput.of(
              Map.of(
                  "REGION", i % 3 == 0 ? "APAC" : (i % 3 == 1 ? "EMEA" : "LATAM"),
                  "AGE", 20 + (i % 60),
                  "SCORE", 500 + (i % 400))));
    }
    return variants;
  }

  @Test
  void parallelBulkMatchesSequentialBulk(@TempDir Path dir) throws Exception {
    try (LoadedRuleset ruleset = load(dir)) {
      List<DecisionInput> variants = variants(500);
      DecisionInput base = DecisionInput.empty();

      BulkResult sequential = ruleset.evaluateBulk(base, variants);

      ExecutorService executor = Executors.newFixedThreadPool(4);
      try {
        BulkResult parallel = ruleset.evaluateBulk(base, variants, executor, 4);

        assertEquals(sequential.size(), parallel.size());
        for (int i = 0; i < sequential.size(); i++) {
          assertEquals(sequential.matched(i), parallel.matched(i), "variant " + i);
          if (sequential.matched(i)) {
            assertEquals(
                sequential.result(i).orElseThrow().ruleId(),
                parallel.result(i).orElseThrow().ruleId(),
                "variant " + i);
          }
        }
      } finally {
        executor.shutdown();
      }
    }
  }

  @Test
  void bulkAppliesBaseOverlayAndPriority(@TempDir Path dir) throws Exception {
    try (LoadedRuleset ruleset = load(dir)) {
      // Base supplies REGION+AGE; variants vary SCORE. Priority 1 (GOLD) must beat 2 (SILVER).
      BulkResult result =
          ruleset.evaluateBulk(
              DecisionInput.of(Map.of("REGION", "APAC", "AGE", 30)),
              List.of(
                  DecisionInput.of(Map.of("SCORE", 800)), DecisionInput.of(Map.of("SCORE", 500))));

      assertEquals("GOLD", result.result(0).orElseThrow().ruleId());
      assertEquals("SILVER", result.result(1).orElseThrow().ruleId());
    }
  }

  @Test
  void explainRendersTheWinningRulesConditions(@TempDir Path dir) throws Exception {
    try (LoadedRuleset ruleset = load(dir)) {
      DecisionOutput output =
          ruleset.explain(DecisionInput.of(Map.of("REGION", "APAC", "AGE", 30, "SCORE", 800)));

      assertEquals("GOLD", output.ruleId());
      assertEquals(new BigDecimal("0.20"), output.outputs().get("DISCOUNT"));

      MatchDiagnostics diagnostics = output.diagnostics().orElseThrow();
      List<String> conditions = diagnostics.matchedConditions();
      assertTrue(conditions.contains("REGION IN (APAC,EMEA)"), "conditions: " + conditions);
      assertTrue(conditions.contains("AGE BETWEEN_INCLUSIVE (18,65)"), "conditions: " + conditions);
      assertTrue(conditions.contains("SCORE GT 700"), "conditions: " + conditions);
    }
  }

  @Test
  void explainOmitsBlankConditionsAndPlainEvaluateCarriesNoDiagnostics(@TempDir Path dir)
      throws Exception {
    try (LoadedRuleset ruleset = load(dir)) {
      // FALLBACK has no conditions at all.
      DecisionOutput fallback = ruleset.explain(DecisionInput.of(Map.of("REGION", "LATAM")));
      assertEquals("FALLBACK", fallback.ruleId());
      assertEquals(List.of(), fallback.diagnostics().orElseThrow().matchedConditions());

      DecisionOutput plain =
          ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC", "AGE", 30, "SCORE", 800)));
      assertFalse(plain.diagnostics().isPresent(), "evaluate() stays diagnostics-free");

      // An empty input satisfies no non-blank condition, so only the all-blank rule matches.
      assertEquals("FALLBACK", ruleset.explain(DecisionInput.empty()).ruleId());
    }
  }
}
