package in.systemhalted.kisoku.api.validation;

import java.util.List;

/** Represents the outcome of validating a decision table. */
public final class ValidationResult {
  private final boolean ok;
  private final List<String> issues;

  private ValidationResult(boolean ok, List<String> issues) {
    this.ok = ok;
    this.issues = issues;
  }

  public static ValidationResult ok() {
    return new ValidationResult(true, List.of());
  }

  public static ValidationResult withIssues(List<String> issues) {
    return new ValidationResult(false, List.copyOf(issues));
  }

  public boolean isOk() {
    return ok;
  }

  public List<String> issues() {
    return issues;
  }
}
