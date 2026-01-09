package in.systemhalted.kisoku.api.validator;

import in.systemhalted.kisoku.api.model.DecisionTableSource;

/** Validates decision table schema and semantics before compilation. */
public interface RulesetValidator {
  ValidationResult validate(DecisionTableSource source);
}
