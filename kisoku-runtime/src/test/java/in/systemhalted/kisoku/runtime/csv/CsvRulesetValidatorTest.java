package in.systemhalted.kisoku.runtime.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.validation.ValidationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Validation coverage: structure, typed operands, duplicates, priority, ranges, and sets. */
class CsvRulesetValidatorTest {

  private final CsvRulesetValidator validator = new CsvRulesetValidator();

  private static final Schema SCHEMA =
      Schema.builder()
          .column("AGE", ColumnType.INTEGER)
          .column("AMOUNT", ColumnType.DECIMAL)
          .column("ACTIVE", ColumnType.BOOLEAN)
          .column("STARTS", ColumnType.DATE)
          .column("SEEN", ColumnType.TIMESTAMP)
          .column("REGION", ColumnType.STRING)
          .column("RESULT", ColumnType.STRING)
          .build();

  private ValidationResult validate(Path dir, String... lines) throws IOException {
    Path csv = dir.resolve("table.csv");
    Files.writeString(csv, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return validator.validate(DecisionTableSources.csv(csv), SCHEMA);
  }

  private static void assertIssueContaining(ValidationResult result, String fragment) {
    assertFalse(result.isOk(), "expected issues, got ok");
    assertTrue(
        result.issues().stream().anyMatch(issue -> issue.contains(fragment)),
        "no issue contains '" + fragment + "': " + result.issues());
  }

  @Test
  void cleanTableValidates(@TempDir Path dir) throws IOException {
    ValidationResult result =
        validate(
            dir,
            "RULE_ID,PRIORITY,AGE,AMOUNT,ACTIVE,STARTS,SEEN,REGION,RESULT",
            "RULE_ID,PRIORITY,GT,BETWEEN,EQ,GTE,LT,IN,SET",
            "R1,1,18,(1.50,99.99),true,2026-01-01,2026-06-01T00:00:00Z,(APAC,EMEA),OK",
            "R2,2,,,,,,,FALLBACK");
    assertTrue(result.isOk(), () -> "unexpected issues: " + result.issues());
  }

  @Test
  void typedOperandsAreCheckedPerColumnType(@TempDir Path dir) throws IOException {
    ValidationResult result =
        validate(
            dir,
            "RULE_ID,AGE,AMOUNT,ACTIVE,STARTS,SEEN,RESULT",
            "RULE_ID,GT,EQ,EQ,GTE,LT,SET",
            "R1,eighteen,1.2.3,banana,01/15/2026,yesterday,OK");

    assertIssueContaining(result, "'eighteen' is not a valid INTEGER");
    assertIssueContaining(result, "'1.2.3' is not a valid DECIMAL");
    assertIssueContaining(result, "'banana' is not a valid BOOLEAN");
    assertIssueContaining(result, "'01/15/2026' is not a valid DATE");
    assertIssueContaining(result, "'yesterday' is not a valid TIMESTAMP");
  }

  @Test
  void typedOperandsInsideRangesAndSetsAreChecked(@TempDir Path dir) throws IOException {
    ValidationResult result =
        validate(
            dir,
            "RULE_ID,AGE,AMOUNT,RESULT",
            "RULE_ID,BETWEEN,IN,SET",
            "R1,(18,old),(1.5,cheap),OK");

    assertIssueContaining(result, "'old' is not a valid INTEGER");
    assertIssueContaining(result, "'cheap' is not a valid DECIMAL");
  }

  @Test
  void invertedRangeIsFlagged(@TempDir Path dir) throws IOException {
    ValidationResult result =
        validate(dir, "RULE_ID,AGE,RESULT", "RULE_ID,BETWEEN,SET", "R1,(65,18),OK");

    assertIssueContaining(result, "range min '65' is greater than max '18'");
  }

  @Test
  void duplicateRuleIdsAreFlagged(@TempDir Path dir) throws IOException {
    ValidationResult result =
        validate(
            dir, "RULE_ID,REGION,RESULT", "RULE_ID,EQ,SET", "R1,APAC,A", "R2,EMEA,B", "R1,LATAM,C");

    assertIssueContaining(result, "duplicate RULE_ID 'R1'");
    assertEquals(1, result.issues().size(), "only the duplicate should be flagged");
  }

  @Test
  void blankAndNonIntegerPriorityAreFlagged(@TempDir Path dir) throws IOException {
    ValidationResult result =
        validate(
            dir,
            "RULE_ID,PRIORITY,REGION,RESULT",
            "RULE_ID,PRIORITY,EQ,SET",
            "R1,,APAC,A",
            "R2,high,EMEA,B");

    assertIssueContaining(result, "empty PRIORITY");
    assertIssueContaining(result, "non-integer PRIORITY 'high'");
  }

  @Test
  void subMicrosecondTimestampIsFlagged(@TempDir Path dir) throws IOException {
    ValidationResult result =
        validate(
            dir, "RULE_ID,SEEN,RESULT", "RULE_ID,GT,SET", "R1,2026-01-15T10:00:00.123456789Z,OK");

    assertIssueContaining(result, "sub-microsecond precision");
  }

  @Test
  void oversizedSetIsFlagged(@TempDir Path dir) throws IOException {
    StringBuilder set = new StringBuilder("(");
    for (int i = 0; i < 65_536; i++) {
      if (i > 0) {
        set.append(',');
      }
      set.append(i);
    }
    set.append(')');
    ValidationResult result =
        validate(dir, "RULE_ID,AGE,RESULT", "RULE_ID,IN,SET", "R1," + set + ",OK");

    assertIssueContaining(result, "the limit is 65535");
  }

  @Test
  void issueCollectionIsCapped(@TempDir Path dir) throws IOException {
    // Every row contributes at least two issues (bad INTEGER + blank output), far exceeding the
    // cap.
    List<String> lines = new java.util.ArrayList<>();
    lines.add("RULE_ID,AGE,RESULT");
    lines.add("RULE_ID,GT,SET");
    for (int i = 0; i < 2_000; i++) {
      lines.add("R" + i + ",notanumber,");
    }
    ValidationResult result = validate(dir, lines.toArray(String[]::new));

    assertIssueContaining(result, "Further issues suppressed after 1000.");
    assertTrue(
        result.issues().size() <= 1_002, "cap should bound issues: " + result.issues().size());
  }

  @Test
  void structuralChecksStillFire(@TempDir Path dir) throws IOException {
    ValidationResult missingRuleId =
        validate(dir, "PRIORITY,REGION,RESULT", "PRIORITY,EQ,SET", "1,APAC,A");
    assertIssueContaining(missingRuleId, "Missing required RULE_ID column");

    ValidationResult noOutputs =
        validate(dir, "RULE_ID,REGION,RESULT", "RULE_ID,EQ,SET", "R1,APAC,");
    assertIssueContaining(noOutputs, "has no output values");

    ValidationResult undeclared =
        validate(dir, "RULE_ID,MYSTERY,RESULT", "RULE_ID,EQ,SET", "R1,x,A");
    assertIssueContaining(undeclared, "Column 'MYSTERY' is not declared in schema");
  }
}
