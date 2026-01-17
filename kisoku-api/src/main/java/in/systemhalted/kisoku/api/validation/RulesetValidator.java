package in.systemhalted.kisoku.api.validation;

import in.systemhalted.kisoku.api.DecisionTableSource;
import in.systemhalted.kisoku.api.Schema;

/** Validates decision table schema and semantics before compilation. */
public interface RulesetValidator {
  /**
   * Validates a decision table against the provided schema.
   *
   * @param source the decision table source
   * @param schema the external schema defining column types
   * @return validation result with any issues found
   */
  ValidationResult validate(DecisionTableSource source, Schema schema);
}
