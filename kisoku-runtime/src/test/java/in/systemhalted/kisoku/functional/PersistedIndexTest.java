package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persisted indexes (format 4.1): artifacts compiled with {@code persistIndexes} (the default)
 * carry per-column index blocks that the loader maps instead of rebuilding; artifacts compiled
 * without them still load and build indexes at load time. Both must evaluate identically, across
 * every indexable operator family, on both the mapped and on-heap read paths.
 */
class PersistedIndexTest {

  private static final Schema SCHEMA =
      Schema.builder()
          .column("REGION", ColumnType.STRING)
          .column("AGE", ColumnType.INTEGER)
          .column("SCORE", ColumnType.INTEGER)
          .column("TIER", ColumnType.STRING)
          .column("DISCOUNT", ColumnType.DECIMAL)
          .build();

  private Path writeTable(Path dir) throws IOException {
    StringBuilder csv =
        new StringBuilder("RULE_ID,PRIORITY,REGION,AGE,SCORE,TIER,DISCOUNT\n")
            .append("RULE_ID,PRIORITY,IN,BETWEEN,GT,NOT IN,SET\n");
    Random random = new Random(7);
    String[] regions = {"(APAC,EMEA)", "(US)", "(LATAM,US)", ""};
    for (int i = 0; i < 200; i++) {
      int lo = random.nextInt(50);
      csv.append("R")
          .append(i)
          .append(',')
          .append(i + 1)
          .append(',')
          .append(regions[random.nextInt(regions.length)])
          .append(",(")
          .append(lo)
          .append(',')
          .append(lo + random.nextInt(40))
          .append("),")
          .append(random.nextInt(900))
          .append(",(BLOCKED),0.")
          .append(10 + random.nextInt(80))
          .append('\n');
    }
    csv.append("FALLBACK,999,,,,,0.01\n");
    Path file = dir.resolve("persist.csv");
    Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void persistedAndRebuiltIndexesEvaluateIdentically(@TempDir Path dir) throws IOException {
    Path csv = writeTable(dir);
    CompiledRuleset withIndexes =
        Kisoku.compiler().compile(DecisionTableSources.csv(csv), CompileOptions.production(SCHEMA));
    CompiledRuleset withoutIndexes =
        Kisoku.compiler()
            .compile(
                DecisionTableSources.csv(csv),
                CompileOptions.production(SCHEMA).withPersistIndexes(false));

    Path persisted = dir.resolve("with.kbin");
    Path bare = dir.resolve("without.kbin");
    withIndexes.writeTo(persisted);
    withoutIndexes.writeTo(bare);

    try (LoadedRuleset mappedPersisted = Kisoku.loader().load(persisted, LoadOptions.memoryMap());
        LoadedRuleset mappedRebuilt = Kisoku.loader().load(bare, LoadOptions.memoryMap());
        LoadedRuleset heapPersisted = Kisoku.loader().load(persisted, LoadOptions.onHeap());
        LoadedRuleset unindexed =
            Kisoku.loader().load(persisted, LoadOptions.memoryMap().withPrewarmIndexes(false))) {

      Random random = new Random(11);
      for (int i = 0; i < 300; i++) {
        Map<String, Object> values = new HashMap<>();
        values.put("REGION", new String[] {"APAC", "EMEA", "US", "LATAM", "ZZ"}[random.nextInt(5)]);
        values.put("AGE", random.nextInt(100));
        values.put("SCORE", random.nextInt(1000));
        if (random.nextBoolean()) {
          values.put("TIER", random.nextBoolean() ? "BLOCKED" : "OK");
        }
        DecisionInput input = DecisionInput.of(values);

        String expected = ruleIdOf(unindexed, input);
        assertEquals(expected, ruleIdOf(mappedPersisted, input), "mapped persisted, probe " + i);
        assertEquals(expected, ruleIdOf(mappedRebuilt, input), "mapped rebuilt, probe " + i);
        assertEquals(expected, ruleIdOf(heapPersisted, input), "on-heap persisted, probe " + i);
      }
    }
  }

  private static String ruleIdOf(LoadedRuleset ruleset, DecisionInput input) {
    try {
      return ruleset.evaluate(input).ruleId();
    } catch (EvaluationException e) {
      return "<no match>";
    }
  }
}
