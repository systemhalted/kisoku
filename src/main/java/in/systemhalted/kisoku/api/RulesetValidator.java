package in.systemhalted.kisoku.api;

/**
 * Validates decision table schema and semantics before compilation.
 */
public interface RulesetValidator {
  ValidationResult validate(DecisionTableSource source);
}
