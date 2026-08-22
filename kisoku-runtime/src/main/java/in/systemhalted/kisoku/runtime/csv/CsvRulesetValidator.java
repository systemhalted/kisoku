package in.systemhalted.kisoku.runtime.csv;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.api.DecisionTableSource;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.TableFormat;
import in.systemhalted.kisoku.api.validation.RulesetValidator;
import in.systemhalted.kisoku.api.validation.ValidationResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Streaming validator for CSV decision tables.
 *
 * <p>Checks, with row/column context on every issue:
 *
 * <ul>
 *   <li>Structure: header and operator rows, per-row column counts, required {@code RULE_ID}, at
 *       least one output column, at least one non-blank output per row.
 *   <li>Schema: every non-reserved column declared, every operand parseable as its column's
 *       declared type ({@code INTEGER}, {@code DECIMAL}, {@code BOOLEAN}, {@code DATE}, {@code
 *       TIMESTAMP}).
 *   <li>Semantics: duplicate {@code RULE_ID}s, blank or non-integer {@code PRIORITY} where the
 *       column exists, ranges with min above max (where the type is ordered by value), sets over
 *       the 65,535-member limit.
 * </ul>
 *
 * <p>Issue collection is capped at {@value #MAX_ISSUES}; validation stops early once the cap is
 * hit, with a final note. Duplicate detection holds seen rule ids on the heap and is bounded at
 * {@value #MAX_TRACKED_RULE_IDS} rows, after which it reports that the check was truncated -
 * validation is an authoring-time gate, not a production-path requirement.
 */
public final class CsvRulesetValidator implements RulesetValidator {

  /** Maximum issues collected before validation stops early. */
  static final int MAX_ISSUES = 1_000;

  /** Maximum rule ids held for duplicate detection. */
  static final int MAX_TRACKED_RULE_IDS = 2_000_000;

  /** Widest set a row can hold, bounded by the artifact's 16-bit length field. */
  private static final int MAX_SET_SIZE = 0xFFFF;

  @Override
  public ValidationResult validate(DecisionTableSource source, Schema schema) {
    List<String> issues = new ArrayList<>();

    if (source.format() != TableFormat.CSV) {
      issues.add("Only CSV sources are supported for validation.");
      return ValidationResult.withIssues(issues);
    }

    try (CsvRowReader reader = new StreamingCsvRowReader(source.openStream())) {
      String[] headerRow = readRow(reader, issues, "Missing header row.");
      String[] operatorRow = readRow(reader, issues, "Missing operator row.");

      if (headerRow == null || operatorRow == null) {
        return ValidationResult.withIssues(issues);
      }

      if (headerRow.length != operatorRow.length) {
        issues.add(
            "Header row has "
                + headerRow.length
                + " columns but operator row has "
                + operatorRow.length);
        return ValidationResult.withIssues(issues);
      }

      Operator[] operators = normalizeOperators(operatorRow, issues, reader.rowNumber());
      validateHeaders(headerRow, operators, schema, issues);

      if (!issues.isEmpty()) {
        return ValidationResult.withIssues(issues);
      }

      ColumnType[] types = resolveTypes(headerRow, operators, schema);
      validateDataRows(reader, headerRow, operators, types, issues);

    } catch (IOException | RuntimeException exception) {
      issues.add("Failed to read CSV source: " + exception.getMessage());
    }

    if (issues.isEmpty()) {
      return ValidationResult.ok();
    }
    return ValidationResult.withIssues(issues);
  }

  private String[] readRow(CsvRowReader reader, List<String> issues, String missingMessage)
      throws IOException {
    String[] row = reader.readNext();
    if (row == null) {
      issues.add(missingMessage);
    }
    return row;
  }

  private Operator[] normalizeOperators(String[] operatorRow, List<String> issues, long rowNumber) {
    Operator[] operators = new Operator[operatorRow.length];
    for (int i = 0; i < operatorRow.length; i++) {
      String raw = operatorRow[i];
      if (raw == null || raw.trim().isEmpty()) {
        issues.add("Row " + rowNumber + " has an empty operator at column " + (i + 1));
        continue;
      }
      try {
        operators[i] = Operator.fromToken(raw);
      } catch (IllegalArgumentException exception) {
        issues.add("Row " + rowNumber + " has unsupported operator: " + raw);
      }
    }
    return operators;
  }

  private void validateHeaders(
      String[] headerRow, Operator[] operators, Schema schema, List<String> issues) {
    boolean hasRuleId = false;
    boolean hasOutput = false;

    for (int i = 0; i < headerRow.length; i++) {
      String columnName = headerRow[i];
      Operator operator = operators[i];

      if (columnName == null || columnName.trim().isEmpty()) {
        issues.add("Column " + (i + 1) + " has an empty name");
        continue;
      }

      if (operator == null) {
        continue;
      }

      if (operator == Operator.RULE_ID) {
        hasRuleId = true;
      }

      if (operator.isOutput()) {
        hasOutput = true;
      }

      if (!isReservedColumn(columnName) && !schema.hasColumn(columnName)) {
        issues.add("Column '" + columnName + "' is not declared in schema");
      }
    }

    if (!hasRuleId) {
      issues.add("Missing required RULE_ID column");
    }

    if (!hasOutput) {
      issues.add("Table must have at least one output column (SET operator)");
    }
  }

  private boolean isReservedColumn(String columnName) {
    return "RULE_ID".equals(columnName) || "PRIORITY".equals(columnName);
  }

  /** Resolves each column's declared type; reserved columns have implicit types. */
  private ColumnType[] resolveTypes(String[] headerRow, Operator[] operators, Schema schema) {
    ColumnType[] types = new ColumnType[headerRow.length];
    for (int i = 0; i < headerRow.length; i++) {
      if (operators[i] == Operator.RULE_ID) {
        types[i] = ColumnType.STRING;
      } else if (operators[i] == Operator.PRIORITY) {
        types[i] = ColumnType.INTEGER;
      } else {
        types[i] = schema.column(headerRow[i]).map(c -> c.type()).orElse(null);
      }
    }
    return types;
  }

  private void validateDataRows(
      CsvRowReader reader,
      String[] headerRow,
      Operator[] operators,
      ColumnType[] types,
      List<String> issues)
      throws IOException {
    int columnCount = headerRow.length;
    int ruleIdIndex = findColumnIndex(operators, Operator.RULE_ID);
    int priorityIndex = findColumnIndex(operators, Operator.PRIORITY);
    int rowCount = 0;

    Set<String> seenRuleIds = new HashSet<>();
    boolean duplicateCheckTruncated = false;

    String[] row;
    while ((row = reader.readNext()) != null) {
      rowCount++;
      long rowNumber = reader.rowNumber();

      if (issues.size() >= MAX_ISSUES) {
        issues.add("Further issues suppressed after " + MAX_ISSUES + ".");
        return;
      }

      if (row.length != columnCount) {
        issues.add("Row " + rowNumber + " has " + row.length + " columns, expected " + columnCount);
        continue;
      }

      if (ruleIdIndex >= 0) {
        String ruleId = trimmed(row[ruleIdIndex]);
        if (ruleId.isEmpty()) {
          issues.add("Row " + rowNumber + " has empty RULE_ID");
        } else if (duplicateCheckTruncated) {
          // Beyond the tracking bound; already reported once.
        } else if (seenRuleIds.size() >= MAX_TRACKED_RULE_IDS) {
          duplicateCheckTruncated = true;
          issues.add(
              "Duplicate RULE_ID check truncated after "
                  + MAX_TRACKED_RULE_IDS
                  + " rows to bound validation memory.");
        } else if (!seenRuleIds.add(ruleId)) {
          issues.add("Row " + rowNumber + " has duplicate RULE_ID '" + ruleId + "'");
        }
      }

      if (priorityIndex >= 0) {
        String priority = trimmed(row[priorityIndex]);
        if (priority.isEmpty()) {
          issues.add("Row " + rowNumber + " has empty PRIORITY (required when the column exists)");
        } else if (!isInteger(priority)) {
          issues.add("Row " + rowNumber + " has non-integer PRIORITY '" + priority + "'");
        }
      }

      boolean hasOutputValue = false;
      for (int i = 0; i < operators.length; i++) {
        Operator operator = operators[i];
        if (operator == null) {
          continue;
        }
        if (operator.isOutput() && !trimmed(row[i]).isEmpty()) {
          hasOutputValue = true;
        }
        validateCellValue(row[i], operator, types[i], headerRow[i], rowNumber, issues);
      }

      if (!hasOutputValue) {
        issues.add("Row " + rowNumber + " has no output values");
      }
    }

    if (rowCount == 0) {
      issues.add("Table has no data rows");
    }
  }

  private int findColumnIndex(Operator[] operators, Operator operator) {
    for (int i = 0; i < operators.length; i++) {
      if (operators[i] == operator) {
        return i;
      }
    }
    return -1;
  }

  private void validateCellValue(
      String value,
      Operator operator,
      ColumnType type,
      String columnName,
      long rowNumber,
      List<String> issues) {
    String cell = trimmed(value);
    if (cell.isEmpty()) {
      return;
    }
    // RULE_ID (any text) and PRIORITY (validated per row above) need no operand check here.
    if (operator == Operator.RULE_ID || operator == Operator.PRIORITY) {
      return;
    }

    switch (operator) {
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE -> {
        if (!cell.startsWith("(") || !cell.endsWith(")")) {
          issues.add(cellIssue(rowNumber, columnName, "range value must be in (min,max) format"));
          return;
        }
        String[] parts = cell.substring(1, cell.length() - 1).split(",", -1);
        if (parts.length != 2) {
          issues.add(
              cellIssue(
                  rowNumber, columnName, "range value must have exactly two parts (min,max)"));
          return;
        }
        String minError = typedOperandError(parts[0].trim(), type);
        String maxError = typedOperandError(parts[1].trim(), type);
        if (minError != null) {
          issues.add(cellIssue(rowNumber, columnName, minError));
        }
        if (maxError != null) {
          issues.add(cellIssue(rowNumber, columnName, maxError));
        }
        if (minError == null
            && maxError == null
            && rangeInverted(parts[0].trim(), parts[1].trim(), type)) {
          issues.add(
              cellIssue(
                  rowNumber,
                  columnName,
                  "range min '"
                      + parts[0].trim()
                      + "' is greater than max '"
                      + parts[1].trim()
                      + "'"));
        }
      }
      case IN, NOT_IN -> {
        if (!cell.startsWith("(") || !cell.endsWith(")")) {
          issues.add(cellIssue(rowNumber, columnName, "set value must be in (a,b,c) format"));
          return;
        }
        String inner = cell.substring(1, cell.length() - 1);
        if (inner.isEmpty()) {
          return;
        }
        String[] members = inner.split(",");
        if (members.length > MAX_SET_SIZE) {
          issues.add(
              cellIssue(
                  rowNumber,
                  columnName,
                  "set has " + members.length + " members; the limit is " + MAX_SET_SIZE));
        }
        for (String member : members) {
          String error = typedOperandError(member.trim(), type);
          if (error != null) {
            issues.add(cellIssue(rowNumber, columnName, error));
          }
        }
      }
      default -> {
        String error = typedOperandError(cell, type);
        if (error != null) {
          issues.add(cellIssue(rowNumber, columnName, error));
        }
      }
    }
  }

  /** Checks one operand against its column type; null means valid. */
  private String typedOperandError(String operand, ColumnType type) {
    if (type == null || operand.isEmpty()) {
      return null;
    }
    return switch (type) {
      case STRING -> null;
      case INTEGER -> isInteger(operand) ? null : "'" + operand + "' is not a valid INTEGER";
      case DECIMAL -> {
        try {
          new BigDecimal(operand);
          yield null;
        } catch (NumberFormatException e) {
          yield "'" + operand + "' is not a valid DECIMAL";
        }
      }
      case BOOLEAN -> {
        String lower = operand.toLowerCase();
        yield switch (lower) {
          case "true", "false", "1", "0", "yes", "no" -> null;
          default -> "'" + operand + "' is not a valid BOOLEAN";
        };
      }
      case DATE -> {
        try {
          LocalDate.parse(operand);
          yield null;
        } catch (DateTimeParseException e) {
          yield "'" + operand + "' is not a valid DATE (expected ISO-8601, e.g. 2026-01-15)";
        }
      }
      case TIMESTAMP -> {
        try {
          Instant parsed = Instant.parse(operand);
          yield parsed.getNano() % 1000 == 0
              ? null
              : "'" + operand + "' has sub-microsecond precision, which cannot be stored";
        } catch (DateTimeParseException e) {
          yield "'"
              + operand
              + "' is not a valid TIMESTAMP (expected ISO-8601 instant, e.g."
              + " 2026-01-15T10:00:00Z)";
        }
      }
    };
  }

  /** True when both bounds parse and min is strictly greater than max under the type's order. */
  private boolean rangeInverted(String min, String max, ColumnType type) {
    if (type == null) {
      return false;
    }
    try {
      return switch (type) {
        case INTEGER -> Long.parseLong(min) > Long.parseLong(max);
        case DECIMAL -> new BigDecimal(min).compareTo(new BigDecimal(max)) > 0;
        case DATE -> LocalDate.parse(min).isAfter(LocalDate.parse(max));
        case TIMESTAMP -> Instant.parse(min).isAfter(Instant.parse(max));
        case STRING -> min.compareTo(max) > 0;
        case BOOLEAN -> false;
      };
    } catch (RuntimeException e) {
      return false; // operand errors are reported separately
    }
  }

  private static String cellIssue(long rowNumber, String columnName, String message) {
    return "Row " + rowNumber + ", column '" + columnName + "': " + message;
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean isInteger(String value) {
    try {
      Long.parseLong(value);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
