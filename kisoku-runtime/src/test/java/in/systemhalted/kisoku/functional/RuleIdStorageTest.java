package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
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
 * RULE_ID roundtrip through its inline UTF-8 column storage (artifact format 3.0).
 *
 * <p>Rule ids are unique per row by design, so they bypass the dictionary; the winning row's id is
 * decoded on demand from the artifact buffer. These tests pin that decode across load modes,
 * including a file-backed memory-mapped load, and for ids the dictionary never sees.
 */
class RuleIdStorageTest {

  private static final Schema SCHEMA =
      Schema.builder()
          .column("REGION", ColumnType.STRING)
          .column("LABEL", ColumnType.STRING)
          .build();

  private CompiledRuleset compile(Path dir) throws IOException {
    Path csv = dir.resolve("ruleids.csv");
    // Multi-byte UTF-8 in an id, an id sharing text with a data value, and a plain id.
    Files.writeString(
        csv,
        String.join(
                "\n",
                "RULE_ID,REGION,LABEL",
                "RULE_ID,EQ,SET",
                "RÜLE-日本-1,APAC,A",
                "APAC,EMEA,B",
                "R3,LATAM,C")
            + "\n",
        StandardCharsets.UTF_8);
    return Kisoku.compiler()
        .compile(DecisionTableSources.csv(csv), CompileOptions.production(SCHEMA));
  }

  private void assertRuleIds(LoadedRuleset ruleset) {
    assertEquals(
        "RÜLE-日本-1",
        ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC"))).ruleId(),
        "multi-byte UTF-8 rule id survives the roundtrip");
    assertEquals(
        "APAC",
        ruleset.evaluate(DecisionInput.of(Map.of("REGION", "EMEA"))).ruleId(),
        "an id that collides with a data value stays distinct from the dictionary");
    assertEquals("R3", ruleset.evaluate(DecisionInput.of(Map.of("REGION", "LATAM"))).ruleId());
  }

  @Test
  void ruleIdsDecodeFromInlineStorageInMemory(@TempDir Path dir) throws IOException {
    CompiledRuleset compiled = compile(dir);
    try (LoadedRuleset ruleset = Kisoku.loader().load(compiled, LoadOptions.onHeap())) {
      assertRuleIds(ruleset);
    }
  }

  @Test
  void ruleIdsDecodeFromFileBackedMemoryMap(@TempDir Path dir) throws IOException {
    CompiledRuleset compiled = compile(dir);
    Path artifact = dir.resolve("ruleids.kbin");
    compiled.writeTo(artifact);
    try (LoadedRuleset ruleset = Kisoku.loader().load(artifact, LoadOptions.memoryMap())) {
      assertRuleIds(ruleset);
    }
  }
}
