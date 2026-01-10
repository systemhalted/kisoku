package in.systemhalted.kisoku.runtime.validator;

import in.systemhalted.kisoku.api.model.DecisionTableSource;
import in.systemhalted.kisoku.api.model.TableFormat;
import in.systemhalted.kisoku.api.validator.RulesetValidator;
import in.systemhalted.kisoku.api.validator.ValidationResult;
import in.systemhalted.kisoku.runtime.csv.CsvRowReader;
import in.systemhalted.kisoku.runtime.csv.StreamingCsvRowReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming validator for CSV decision tables.
 *
 * <p>TODO: implement header validation, per-row validation, and operator parsing.
 */
public final class CsvRulesetValidator implements RulesetValidator {
  private static final List<String> SUPPORTED_OPERATORS =
      List.of(
          "RULE_ID",
          "PRIORITY",
          "SET",
          "EQ",
          "NE",
          "GT",
          "GTE",
          "LT",
          "LTE",
          "BETWEEN_INCLUSIVE",
          "BETWEEN_EXCLUSIVE",
          "NOT_BETWEEN_INCLUSIVE",
          "NOT_BETWEEN_EXCLUSIVE",
          "IN",
          "NOT IN",
          "=",
          "!=",
          ">",
          ">=",
          "<",
          "<=",
          "BETWEEN",
          "NOT BETWEEN");

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
      // TODO: read rows and populate issues list.
    } catch (IOException exception) {
      issues.add("Failed to read CSV source: " + exception.getMessage());
    }

    // TODO: remove this placeholder when validation is implemented.
    if (issues.isEmpty()) {
      issues.add("TODO: CSV validation not implemented.");
    }

    return ValidationResult.withIssues(issues);
  }
}
