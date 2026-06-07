package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies loading a ruleset from an on-disk artifact (memory-mapped) produces results identical to
 * loading the same artifact from memory, and that {@link LoadedRuleset#close()} is safe and
 * idempotent.
 */
class FileBackedLoadTest {
  private final RulesetLoader loader = Kisoku.loader();

  private CompiledRuleset compileSample(Path dir) throws IOException {
    Path csv = dir.resolve("sample.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
      writer.write("RULE_ID,PRIORITY,REGION,AGE,DISCOUNT\n");
      writer.write("RULE_ID,PRIORITY,IN,BETWEEN,SET\n");
      writer.write("R1,30,(APAC,EMEA),(18,65),0.20\n");
      writer.write("R2,20,(US),(21,70),0.15\n");
      writer.write("R3,10,,,0.05\n");
    }
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("AGE", ColumnType.INTEGER)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .build();
    return Kisoku.compiler()
        .compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));
  }

  @Test
  void mappedFileLoadMatchesInMemoryLoad(@TempDir Path tempDir) throws IOException {
    CompiledRuleset compiled = compileSample(tempDir);
    Path artifact = tempDir.resolve("ruleset.kss");
    compiled.writeTo(artifact);

    List<Map<String, Object>> inputs =
        List.of(
            Map.of("REGION", "APAC", "AGE", 40),
            Map.of("REGION", "US", "AGE", 50),
            Map.of("REGION", "ANTARCTICA", "AGE", 25),
            Map.of("REGION", "EMEA", "AGE", 10));

    try (LoadedRuleset fromMemory = loader.load(compiled, LoadOptions.memoryMap());
        LoadedRuleset fromFile = loader.load(artifact, LoadOptions.memoryMap())) {
      for (Map<String, Object> values : inputs) {
        DecisionInput input = DecisionInput.of(values);
        DecisionOutput memOut = fromMemory.evaluate(input);
        DecisionOutput fileOut = fromFile.evaluate(input);
        assertEquals(memOut.ruleId(), fileOut.ruleId(), "ruleId mismatch for " + values);
        assertEquals(memOut.outputs(), fileOut.outputs(), "outputs mismatch for " + values);
      }
    }
  }

  @Test
  void concurrentEvaluationOnMappedRulesetIsConsistent(@TempDir Path tempDir) throws Exception {
    CompiledRuleset compiled = compileSample(tempDir);
    Path artifact = tempDir.resolve("ruleset.kss");
    compiled.writeTo(artifact);

    DecisionInput input = DecisionInput.of(Map.of("REGION", "APAC", "AGE", 40));

    try (LoadedRuleset fromFile = loader.load(artifact, LoadOptions.memoryMap())) {
      String expected = fromFile.evaluate(input).ruleId();

      // Many threads share one mapped buffer; decoders read it via absolute offsets, so concurrent
      // evaluation must be consistent without any synchronization.
      int threads = 16;
      int iterationsPerThread = 2_000;
      var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
      try {
        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
        for (int t = 0; t < threads; t++) {
          tasks.add(
              () -> {
                for (int i = 0; i < iterationsPerThread; i++) {
                  if (!expected.equals(fromFile.evaluate(input).ruleId())) {
                    return false;
                  }
                }
                return true;
              });
        }
        for (var future : pool.invokeAll(tasks)) {
          assertTrue(future.get(), "concurrent evaluation produced an inconsistent result");
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }

  @Test
  void closeIsIdempotent(@TempDir Path tempDir) throws IOException {
    CompiledRuleset compiled = compileSample(tempDir);
    Path artifact = tempDir.resolve("ruleset.kss");
    compiled.writeTo(artifact);

    LoadedRuleset ruleset = loader.load(artifact, LoadOptions.memoryMap());
    // Evaluate before close to ensure the mapping is live.
    assertNotNull(ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC", "AGE", 40))).ruleId());

    ruleset.close();
    assertDoesNotThrow(ruleset::close, "second close should be a no-op");
  }
}
