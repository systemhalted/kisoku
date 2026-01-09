package in.systemhalted.kisoku.api;

import java.util.List;

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

    public boolean ok() {
        return ok;
    }

    public List<String> issues() {
        return issues;
    }
}
