package in.systemhalted.kisoku.functional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ordering and equality semantics per column type.
 *
 * <p>Regression: every type was squeezed into a 4-byte code that did not preserve the value's own
 * order - STRING, DECIMAL and TIMESTAMP were stored as dictionary IDs assigned in first-seen order,
 * and INTEGER was narrowed to 32 bits. Ordering operators on those types therefore compared
 * insertion order rather than value, matching nothing at all for inputs the compiler had never
 * seen, and {@code 0.50} did not equal {@code 0.5}.
 *
 * <p>Each case runs on both the indexed and the non-indexed path.
 */
class ValueOrderingTest {
  private final RulesetCompiler compiler = Kisoku.compiler();
  private final RulesetLoader loader = Kisoku.loader();

  @Test
  void decimalComparesNumericallyNotByInsertionOrder(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir, "decimal-gt.csv", "RULE_ID,AMOUNT,TIER", "RULE_ID,GT,SET", "BIG,100.00,PREMIUM");
    Schema schema = schemaOf("AMOUNT", ColumnType.DECIMAL, "TIER", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          // "1000.00" sorts before "100.00" as text; numerically it is larger.
          assertEquals("BIG", ruleId(ruleset, "AMOUNT", new BigDecimal("1000.00")));
          assertEquals(Optional.empty(), match(ruleset, "AMOUNT", new BigDecimal("5.00")));
          assertEquals(Optional.empty(), match(ruleset, "AMOUNT", new BigDecimal("100.00")));
          assertEquals("BIG", ruleId(ruleset, "AMOUNT", new BigDecimal("100.01")));
        });
  }

  @Test
  void decimalEqualityIgnoresScale(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(dir, "decimal-eq.csv", "RULE_ID,AMOUNT,TIER", "RULE_ID,EQ,SET", "HALF,0.5,FIFTY");
    Schema schema = schemaOf("AMOUNT", ColumnType.DECIMAL, "TIER", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          assertEquals("HALF", ruleId(ruleset, "AMOUNT", new BigDecimal("0.5")));
          assertEquals("HALF", ruleId(ruleset, "AMOUNT", new BigDecimal("0.50")));
          assertEquals("HALF", ruleId(ruleset, "AMOUNT", 0.5d));
          assertEquals(Optional.empty(), match(ruleset, "AMOUNT", new BigDecimal("0.6")));
        });
  }

  /**
   * An input carrying more precision than the column stores must still order correctly against the
   * stored boundary, and must not compare equal to it.
   */
  @Test
  void decimalInputFinerThanColumnScaleOrdersCorrectly(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "decimal-precision.csv",
            "RULE_ID,AMOUNT,TIER",
            "RULE_ID,GT,SET",
            "ABOVE,10.00,HIGH");
    Schema schema = schemaOf("AMOUNT", ColumnType.DECIMAL, "TIER", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          assertEquals("ABOVE", ruleId(ruleset, "AMOUNT", new BigDecimal("10.001")));
          assertEquals(Optional.empty(), match(ruleset, "AMOUNT", new BigDecimal("9.999")));
        });
  }

  @Test
  void decimalRangeUsesNumericBounds(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "decimal-between.csv",
            "RULE_ID,AMOUNT,TIER",
            "RULE_ID,BETWEEN,SET",
            "MID,(9.50,100.25),MIDDLE");
    Schema schema = schemaOf("AMOUNT", ColumnType.DECIMAL, "TIER", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          assertEquals("MID", ruleId(ruleset, "AMOUNT", new BigDecimal("9.50")));
          assertEquals("MID", ruleId(ruleset, "AMOUNT", new BigDecimal("50.00")));
          assertEquals("MID", ruleId(ruleset, "AMOUNT", new BigDecimal("100.25")));
          assertEquals(Optional.empty(), match(ruleset, "AMOUNT", new BigDecimal("100.26")));
          assertEquals(Optional.empty(), match(ruleset, "AMOUNT", new BigDecimal("9.49")));
        });
  }

  @Test
  void decimalOutputsDecodeAsDecimalAtColumnScale(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "decimal-out.csv",
            "RULE_ID,REGION,DISCOUNT",
            "RULE_ID,EQ,SET",
            "APAC_RULE,APAC,0.05",
            "EMEA_RULE,EMEA,0.125");
    Schema schema = schemaOf("REGION", ColumnType.STRING, "DISCOUNT", ColumnType.DECIMAL);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          // Column scale is the widest in the column (3), so every value decodes at that scale.
          assertEquals(
              new BigDecimal("0.050"),
              ruleset
                  .evaluate(DecisionInput.of(Map.of("REGION", "APAC")))
                  .outputs()
                  .get("DISCOUNT"));
          assertEquals(
              new BigDecimal("0.125"),
              ruleset
                  .evaluate(DecisionInput.of(Map.of("REGION", "EMEA")))
                  .outputs()
                  .get("DISCOUNT"));
        });
  }

  /**
   * Values whose code is 0 must still decode. Regression: decoding treated code 0 as "no value",
   * but decimal zero, {@code 1970-01-01}, the epoch instant and {@code false} all encode to 0, so a
   * legitimate output silently became null - and then failed the output map's null check.
   */
  @Test
  void zeroValuedOutputsDecodeRatherThanVanishing(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "zero-outputs.csv",
            "RULE_ID,REGION,DISCOUNT,STARTS_ON,ACTIVE",
            "RULE_ID,EQ,SET,SET,SET",
            "ZEROES,APAC,0.00,1970-01-01,false");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .column("STARTS_ON", ColumnType.DATE)
            .column("ACTIVE", ColumnType.BOOLEAN)
            .build();

    forEachPath(
        csv,
        schema,
        ruleset -> {
          Map<String, Object> outputs =
              ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC"))).outputs();
          assertEquals(new BigDecimal("0.00"), outputs.get("DISCOUNT"));
          assertEquals(LocalDate.of(1970, 1, 1), outputs.get("STARTS_ON"));
          assertEquals(false, outputs.get("ACTIVE"));
        });
  }

  /** A blank output cell yields no key at all, rather than a null value. */
  @Test
  void blankOutputCellsAreOmitted(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "blank-output.csv",
            "RULE_ID,REGION,DISCOUNT,NOTE",
            "RULE_ID,EQ,SET,SET",
            "PARTIAL,APAC,0.05,");
    Schema schema =
        Schema.builder()
            .column("REGION", ColumnType.STRING)
            .column("DISCOUNT", ColumnType.DECIMAL)
            .column("NOTE", ColumnType.STRING)
            .build();

    forEachPath(
        csv,
        schema,
        ruleset -> {
          Map<String, Object> outputs =
              ruleset.evaluate(DecisionInput.of(Map.of("REGION", "APAC"))).outputs();
          assertEquals(new BigDecimal("0.05"), outputs.get("DISCOUNT"));
          assertEquals(false, outputs.containsKey("NOTE"), "blank output cell should be omitted");
        });
  }

  @Test
  void stringComparesLexicographically(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(dir, "string-gt.csv", "RULE_ID,NAME,BUCKET", "RULE_ID,GT,SET", "AFTER_M,MMM,LATE");
    Schema schema = schemaOf("NAME", ColumnType.STRING, "BUCKET", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          // Neither input appears in the table, so both must be ranked against the dictionary.
          assertEquals("AFTER_M", ruleId(ruleset, "NAME", "ZZZ"));
          assertEquals(Optional.empty(), match(ruleset, "NAME", "AAA"));
          assertEquals(Optional.empty(), match(ruleset, "NAME", "MMM"));
          assertEquals("AFTER_M", ruleId(ruleset, "NAME", "MMMA"));
        });
  }

  @Test
  void stringRangeUsesLexicographicBounds(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "string-between.csv",
            "RULE_ID,NAME,BUCKET",
            "RULE_ID,BETWEEN,SET",
            "D_TO_M,(DDD,MMM),MIDDLE");
    Schema schema = schemaOf("NAME", ColumnType.STRING, "BUCKET", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          assertEquals("D_TO_M", ruleId(ruleset, "NAME", "DDD"));
          assertEquals("D_TO_M", ruleId(ruleset, "NAME", "GGG"));
          assertEquals("D_TO_M", ruleId(ruleset, "NAME", "MMM"));
          assertEquals(Optional.empty(), match(ruleset, "NAME", "AAA"));
          assertEquals(Optional.empty(), match(ruleset, "NAME", "ZZZ"));
        });
  }

  @Test
  void integerKeepsFullSixtyFourBitRange(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "integer.csv",
            "RULE_ID,ACCOUNT,FLAG",
            "RULE_ID,EQ,SET",
            "SMALL,1,ONE",
            "LARGE,4294967297,BIG");
    Schema schema = schemaOf("ACCOUNT", ColumnType.INTEGER, "FLAG", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          // 2^32 + 1 narrows to 1 in 32 bits; it must not collide with the rule for 1.
          assertEquals("LARGE", ruleId(ruleset, "ACCOUNT", 4294967297L));
          assertEquals("SMALL", ruleId(ruleset, "ACCOUNT", 1));
        });
  }

  @Test
  void dateComparesChronologically(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir, "date.csv", "RULE_ID,EFFECTIVE,STATUS", "RULE_ID,GTE,SET", "ACTIVE,2026-01-15,ON");
    Schema schema = schemaOf("EFFECTIVE", ColumnType.DATE, "STATUS", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          assertEquals("ACTIVE", ruleId(ruleset, "EFFECTIVE", LocalDate.of(2026, 1, 15)));
          assertEquals("ACTIVE", ruleId(ruleset, "EFFECTIVE", LocalDate.of(2026, 6, 1)));
          assertEquals(Optional.empty(), match(ruleset, "EFFECTIVE", LocalDate.of(2025, 12, 31)));
        });
  }

  @Test
  void timestampComparesChronologically(@TempDir Path dir) throws IOException {
    Path csv =
        writeCsv(
            dir,
            "timestamp.csv",
            "RULE_ID,SEEN_AT,STATUS",
            "RULE_ID,GT,SET",
            "RECENT,2026-01-15T10:00:00Z,FRESH");
    Schema schema = schemaOf("SEEN_AT", ColumnType.TIMESTAMP, "STATUS", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset -> {
          assertEquals("RECENT", ruleId(ruleset, "SEEN_AT", Instant.parse("2026-01-15T10:00:01Z")));
          assertEquals(
              Optional.empty(), match(ruleset, "SEEN_AT", Instant.parse("2026-01-15T09:59:59Z")));
          assertEquals(
              Optional.empty(), match(ruleset, "SEEN_AT", Instant.parse("2026-01-15T10:00:00Z")));
        });
  }

  // ----------------------------------------------------------------- helpers

  private void forEachPath(Path csv, Schema schema, java.util.function.Consumer<LoadedRuleset> body)
      throws IOException {
    CompiledRuleset compiled =
        compiler.compile(DecisionTableSources.csv(csv), CompileOptions.production(schema));
    for (boolean indexed : new boolean[] {true, false}) {
      try (LoadedRuleset ruleset =
          loader.load(compiled, LoadOptions.onHeap().withPrewarmIndexes(indexed))) {
        body.accept(ruleset);
      }
    }
  }

  private static String ruleId(LoadedRuleset ruleset, String column, Object value) {
    return ruleset.evaluate(DecisionInput.of(Map.of(column, value))).ruleId();
  }

  /** Returns the matched rule ID, or empty when no rule matches. */
  private static Optional<String> match(LoadedRuleset ruleset, String column, Object value) {
    try {
      return Optional.of(ruleId(ruleset, column, value));
    } catch (EvaluationException e) {
      return Optional.empty();
    }
  }

  private static Schema schemaOf(String a, ColumnType typeA, String b, ColumnType typeB) {
    return Schema.builder().column(a, typeA).column(b, typeB).build();
  }

  private static Path writeCsv(Path dir, String name, String... lines) throws IOException {
    Path path = dir.resolve(name);
    Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return path;
  }

  /** Guards against the helper silently swallowing a different failure. */
  @Test
  void matchHelperOnlySwallowsNoMatch(@TempDir Path dir) throws IOException {
    Path csv = writeCsv(dir, "typed.csv", "RULE_ID,AGE,LABEL", "RULE_ID,EQ,SET", "ADULT,18,GROWN");
    Schema schema = schemaOf("AGE", ColumnType.INTEGER, "LABEL", ColumnType.STRING);

    forEachPath(
        csv,
        schema,
        ruleset ->
            assertThrows(
                EvaluationException.class,
                () -> ruleId(ruleset, "AGE", "not-a-number"),
                "a type error must surface, not read as 'no match'"));
  }
}
