package in.systemhalted.kisoku.api;

public interface RulesetValidator {
    ValidationResult validate(DecisionTableSource source);
}
