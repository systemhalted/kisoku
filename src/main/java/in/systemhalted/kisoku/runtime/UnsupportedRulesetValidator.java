package in.systemhalted.kisoku.runtime;

import in.systemhalted.kisoku.api.DecisionTableSource;
import in.systemhalted.kisoku.api.RulesetValidator;
import in.systemhalted.kisoku.api.ValidationResult;

/**
 * Placeholder validator that throws until a real implementation is provided.
 */
public final class UnsupportedRulesetValidator implements RulesetValidator {
  @Override
  public ValidationResult validate(DecisionTableSource source) {
    throw new UnsupportedOperationException("Ruleset validation is not implemented yet.");
  }
}
