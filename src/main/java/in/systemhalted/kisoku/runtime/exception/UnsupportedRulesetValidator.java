package in.systemhalted.kisoku.runtime.exception;

import in.systemhalted.kisoku.api.model.DecisionTableSource;
import in.systemhalted.kisoku.api.model.Schema;
import in.systemhalted.kisoku.api.validator.RulesetValidator;
import in.systemhalted.kisoku.api.validator.ValidationResult;

/** Placeholder validator that throws until a real implementation is provided. */
public final class UnsupportedRulesetValidator implements RulesetValidator {
  @Override
  public ValidationResult validate(DecisionTableSource source, Schema schema) {
    throw new UnsupportedOperationException("Ruleset validation is not implemented yet.");
  }
}
