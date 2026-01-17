package in.systemhalted.kisoku.runtime.validator;

import in.systemhalted.kisoku.api.model.DecisionTableSource;
import in.systemhalted.kisoku.api.model.Schema;
import in.systemhalted.kisoku.api.model.TableFormat;
import in.systemhalted.kisoku.api.validator.RulesetValidator;
import in.systemhalted.kisoku.api.validator.ValidationResult;
import in.systemhalted.kisoku.runtime.csv.CsvRowReader;
import in.systemhalted.kisoku.runtime.csv.StreamingCsvRowReader;
import in.systemhalted.kisoku.runtime.operator.Operator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Streaming validator for CSV decision tables. */
public final class CsvRulesetValidator implements RulesetValidator {

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

      validateDataRows(reader, headerRow, operators, issues);

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

  private void validateDataRows(
      CsvRowReader reader, String[] headerRow, Operator[] operators, List<String> issues)
      throws IOException {
    int columnCount = headerRow.length;
    int ruleIdIndex = findColumnIndex(headerRow, "RULE_ID");
    int rowCount = 0;

    String[] row;
    while ((row = reader.readNext()) != null) {
      rowCount++;
      long rowNumber = reader.rowNumber();

      if (row.length != columnCount) {
        issues.add("Row " + rowNumber + " has " + row.length + " columns, expected " + columnCount);
        continue;
      }

      if (ruleIdIndex >= 0) {
        String ruleId = row[ruleIdIndex];
        if (ruleId == null || ruleId.trim().isEmpty()) {
          issues.add("Row " + rowNumber + " has empty RULE_ID");
        }
      }

      boolean hasOutputValue = false;
      for (int i = 0; i < operators.length; i++) {
        Operator operator = operators[i];
        if (operator != null && operator.isOutput()) {
          String value = row[i];
          if (value != null && !value.trim().isEmpty()) {
            hasOutputValue = true;
          }
        }

        if (operator != null) {
          validateCellValue(row[i], operator, headerRow[i], rowNumber, issues);
        }
      }

      if (!hasOutputValue) {
        issues.add("Row " + rowNumber + " has no output values");
      }
    }

    if (rowCount == 0) {
      issues.add("Table has no data rows");
    }
  }

  private int findColumnIndex(String[] headerRow, String columnName) {
    for (int i = 0; i < headerRow.length; i++) {
      if (columnName.equals(headerRow[i])) {
        return i;
      }
    }
    return -1;
  }

  private void validateCellValue(
      String value, Operator operator, String columnName, long rowNumber, List<String> issues) {
    if (value == null || value.trim().isEmpty()) {
      return;
    }

    switch (operator) {
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE -> {
        if (!value.startsWith("(") || !value.endsWith(")")) {
          issues.add(
              "Row "
                  + rowNumber
                  + ", column '"
                  + columnName
                  + "': range value must be in (min,max) format");
        } else {
          String inner = value.substring(1, value.length() - 1);
          String[] parts = inner.split(",", -1);
          if (parts.length != 2) {
            issues.add(
                "Row "
                    + rowNumber
                    + ", column '"
                    + columnName
                    + "': range value must have exactly two parts (min,max)");
          }
        }
      }
      case IN, NOT_IN -> {
        if (!value.startsWith("(") || !value.endsWith(")")) {
          issues.add(
              "Row "
                  + rowNumber
                  + ", column '"
                  + columnName
                  + "': set value must be in (a,b,c) format");
        }
      }
      default -> {
        // Scalar values don't need special format validation
      }
    }
  }
}
