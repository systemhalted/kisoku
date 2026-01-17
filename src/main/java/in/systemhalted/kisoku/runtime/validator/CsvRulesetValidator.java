package in.systemhalted.kisoku.runtime.validator;

import in.systemhalted.kisoku.api.model.DecisionTableSource;
import in.systemhalted.kisoku.api.model.TableFormat;
import in.systemhalted.kisoku.api.validator.RulesetValidator;
import in.systemhalted.kisoku.api.validator.ValidationResult;
import in.systemhalted.kisoku.runtime.csv.CsvRowReader;
import in.systemhalted.kisoku.runtime.csv.StreamingCsvRowReader;
import in.systemhalted.kisoku.runtime.operator.Operator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming validator for CSV decision tables.
 *
 * <p>TODO: implement header validation, per-row validation, and operator parsing.
 */
public final class CsvRulesetValidator implements RulesetValidator {
  // TODO: use Operator.fromToken(...) to validate and normalize operator row tokens.

  @Override
  public ValidationResult validate(DecisionTableSource source) {
    List<String> issues = new ArrayList<>();

    // TODO: validate source.format() is CSV; otherwise return a validation issue.
    if (source.format() != TableFormat.CSV) {
      issues.add("Only CSV sources are supported for validation.");
      return ValidationResult.withIssues(issues);
    }

    // TODO: stream the CSV with StreamingCsvRowReader and validate:
    // 1) header row (column names) and operator row
    // 2) data rows with required RULE_ID and PRIORITY (if present)
    // 3) operator-specific cell encoding and output rules
    // 4) at least one output value per row
    // 5) error collection with row/column context
    try (CsvRowReader reader = new StreamingCsvRowReader(source.openStream())) {
      // TODO: read header row and operator row.
      String[] headerRow = readRow(reader, issues, "Missing header row.");
      String[] operatorRow = readRow(reader, issues, "Missing operator row.");

      if (headerRow != null && operatorRow != null) {
        normalizeOperators(operatorRow, issues, reader.rowNumber());
      }

      // TODO: read data rows and apply per-row validation.
    } catch (IOException | RuntimeException exception) {
      issues.add("Failed to read CSV source: " + exception.getMessage());
    }

    // TODO: remove this placeholder when validation is implemented.
    if (issues.isEmpty()) {
      issues.add("TODO: CSV validation not implemented.");
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
}
