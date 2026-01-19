package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.api.validation.RulesetValidator;
import in.systemhalted.kisoku.api.validation.ValidationResult;
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
 * Integration tests for indexed evaluation.
 *
 * <p>Verifies that indexed evaluation produces identical results to linear evaluation for various
 * table configurations.
 */
class IndexedEvaluationTest {
  private final RulesetValidator validator = Kisoku.validator();
  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  @Test
  void indexedEvaluationMatchesLinearForEqualityColumns(@TempDir Path tempDir) throws IOException {
    Path csv = writeEqualityTable(tempDir);
    Schema schema = equalityTableSchema();

    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    CompiledRuleset compiled =
        compiler.compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));

    // Test with indexing enabled (prewarmIndexes=true, which is the default)
    try (LoadedRuleset indexedRuleset = loader.load(compiled, LoadOptions.memoryMap())) {
      // Test with indexing disabled
      try (LoadedRuleset linearRuleset =
          loader.load(compiled, LoadOptions.memoryMap().withPrewarmIndexes(false))) {

        // Test multiple inputs
        List<Map<String, Object>> testInputs =
            List.of(
                Map.of("COUNTRY", "USA", "STATUS", "ACTIVE"),
                Map.of("COUNTRY", "UK", "STATUS", "ACTIVE"),
                Map.of("COUNTRY", "USA", "STATUS", "INACTIVE"),
                Map.of("COUNTRY", "GERMANY", "STATUS", "PENDING"),
                Map.of("COUNTRY", "FRANCE", "STATUS", "ACTIVE"));

        for (Map<String, Object> inputValues : testInputs) {
          DecisionInput input = DecisionInput.of(inputValues);

          DecisionOutput indexedResult = indexedRuleset.evaluate(input);
          DecisionOutput linearResult = linearRuleset.evaluate(input);

          assertEquals(
              linearResult.ruleId(),
              indexedResult.ruleId(),
              "Rule ID mismatch for input: " + inputValues);
          assertEquals(
              linearResult.outputs(),
              indexedResult.outputs(),
              "Outputs mismatch for input: " + inputValues);
        }
      }
    }
  }

  @Test
  void indexedEvaluationHandlesBlankCells(@TempDir Path tempDir) throws IOException {
    Path csv = writeTableWithBlanks(tempDir);
    Schema schema = tableWithBlanksSchema();

    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    CompiledRuleset compiled =
        compiler.compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));

    try (LoadedRuleset indexedRuleset = loader.load(compiled, LoadOptions.memoryMap())) {
      try (LoadedRuleset linearRuleset =
          loader.load(compiled, LoadOptions.memoryMap().withPrewarmIndexes(false))) {

        // Input that matches R1 (exact match on CATEGORY)
        DecisionInput input1 = DecisionInput.of(Map.of("CATEGORY", "ELECTRONICS", "TIER", "GOLD"));
        assertEquals(
            linearRuleset.evaluate(input1).ruleId(), indexedRuleset.evaluate(input1).ruleId());

        // Input that matches R2 (CATEGORY=BOOKS)
        DecisionInput input2 = DecisionInput.of(Map.of("CATEGORY", "BOOKS", "TIER", "SILVER"));
        assertEquals(
            linearRuleset.evaluate(input2).ruleId(), indexedRuleset.evaluate(input2).ruleId());

        // Input that matches R3 (blank CATEGORY matches anything)
        DecisionInput input3 = DecisionInput.of(Map.of("CATEGORY", "FURNITURE", "TIER", "BRONZE"));
        assertEquals(
            linearRuleset.evaluate(input3).ruleId(), indexedRuleset.evaluate(input3).ruleId());
      }
    }
  }

  @Test
  void indexedEvaluationWithMixedOperators(@TempDir Path tempDir) throws IOException {
    // Tests that indexed columns work correctly alongside non-indexed columns
    Path csv = writeMixedOperatorTable(tempDir);
    Schema schema = mixedOperatorTableSchema();

    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    CompiledRuleset compiled =
        compiler.compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));

    try (LoadedRuleset indexedRuleset = loader.load(compiled, LoadOptions.memoryMap())) {
      try (LoadedRuleset linearRuleset =
          loader.load(compiled, LoadOptions.memoryMap().withPrewarmIndexes(false))) {

        // EQ column + BETWEEN column
        DecisionInput input1 = DecisionInput.of(Map.of("TYPE", "PREMIUM", "AMOUNT", 500));
        assertEquals(
            linearRuleset.evaluate(input1).ruleId(), indexedRuleset.evaluate(input1).ruleId());

        // EQ column + IN column
        DecisionInput input2 = DecisionInput.of(Map.of("TYPE", "STANDARD", "AMOUNT", 100));
        assertEquals(
            linearRuleset.evaluate(input2).ruleId(), indexedRuleset.evaluate(input2).ruleId());
      }
    }
  }

  @Test
  void indexedEvaluationWithLargerTable(@TempDir Path tempDir) throws IOException {
    // Tests indexed evaluation with a larger table (1000 rows)
    int rowCount = 1000;
    Path csv = writeLargeEqualityTable(tempDir, rowCount);
    Schema schema = largeEqualityTableSchema();

    ValidationResult validation = validator.validate(DecisionTableSources.csv(csv), schema);
    assertTrue(validation.isOk(), () -> "Validation failed: " + validation.issues());

    CompiledRuleset compiled =
        compiler.compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));

    try (LoadedRuleset indexedRuleset = loader.load(compiled, LoadOptions.memoryMap())) {
      try (LoadedRuleset linearRuleset =
          loader.load(compiled, LoadOptions.memoryMap().withPrewarmIndexes(false))) {

        // Test 50 random inputs
        for (int i = 0; i < 50; i++) {
          String code = "CODE_" + (i * 20 % rowCount);
          DecisionInput input = DecisionInput.of(Map.of("CODE", code));

          DecisionOutput indexedResult = indexedRuleset.evaluate(input);
          DecisionOutput linearResult = linearRuleset.evaluate(input);

          assertEquals(
              linearResult.ruleId(), indexedResult.ruleId(), "Rule ID mismatch for CODE=" + code);
        }
      }
    }
  }

  // --- Helper methods for creating test tables ---

  private Schema equalityTableSchema() {
    return Schema.builder()
        .column("COUNTRY", ColumnType.STRING)
        .column("STATUS", ColumnType.STRING)
        .column("RATE", ColumnType.DECIMAL)
        .build();
  }

  private Path writeEqualityTable(Path dir) throws IOException {
    Path path = dir.resolve("equality.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("RULE_ID,COUNTRY,STATUS,RATE\n");
      writer.write("RULE_ID,EQ,EQ,SET\n");
      writer.write("R1,USA,ACTIVE,0.05\n");
      writer.write("R2,USA,INACTIVE,0.02\n");
      writer.write("R3,UK,ACTIVE,0.07\n");
      writer.write("R4,UK,INACTIVE,0.03\n");
      writer.write("R5,GERMANY,ACTIVE,0.06\n");
      writer.write("R6,GERMANY,PENDING,0.04\n");
      writer.write("R7,FRANCE,ACTIVE,0.08\n");
    }
    return path;
  }

  private Schema tableWithBlanksSchema() {
    return Schema.builder()
        .column("CATEGORY", ColumnType.STRING)
        .column("TIER", ColumnType.STRING)
        .column("DISCOUNT", ColumnType.DECIMAL)
        .build();
  }

  private Path writeTableWithBlanks(Path dir) throws IOException {
    Path path = dir.resolve("blanks.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("RULE_ID,PRIORITY,CATEGORY,TIER,DISCOUNT\n");
      writer.write("RULE_ID,PRIORITY,EQ,EQ,SET\n");
      // R1: specific match
      writer.write("R1,30,ELECTRONICS,GOLD,0.20\n");
      // R2: specific CATEGORY, any TIER (blank TIER)
      writer.write("R2,20,BOOKS,,0.15\n");
      // R3: any CATEGORY (blank), specific TIER
      writer.write("R3,10,,BRONZE,0.05\n");
    }
    return path;
  }

  private Schema mixedOperatorTableSchema() {
    return Schema.builder()
        .column("TYPE", ColumnType.STRING)
        .column("AMOUNT", ColumnType.INTEGER)
        .column("RESULT", ColumnType.STRING)
        .build();
  }

  private Path writeMixedOperatorTable(Path dir) throws IOException {
    Path path = dir.resolve("mixed.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("RULE_ID,PRIORITY,TYPE,AMOUNT,RESULT\n");
      writer.write("RULE_ID,PRIORITY,EQ,BETWEEN,SET\n");
      writer.write("R1,30,PREMIUM,(100,1000),HIGH\n");
      writer.write("R2,20,STANDARD,(0,500),LOW\n");
      writer.write("R3,10,BASIC,(0,100),MINIMAL\n");
    }
    return path;
  }

  private Schema largeEqualityTableSchema() {
    return Schema.builder()
        .column("CODE", ColumnType.STRING)
        .column("VALUE", ColumnType.STRING)
        .build();
  }

  private Path writeLargeEqualityTable(Path dir, int rowCount) throws IOException {
    Path path = dir.resolve("large-equality.csv");
    try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      writer.write("RULE_ID,CODE,VALUE\n");
      writer.write("RULE_ID,EQ,SET\n");
      for (int i = 0; i < rowCount; i++) {
        writer.write("R" + i + ",CODE_" + i + ",VALUE_" + i + "\n");
      }
    }
    return path;
  }
}
