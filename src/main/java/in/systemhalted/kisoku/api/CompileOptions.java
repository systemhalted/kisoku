package in.systemhalted.kisoku.api;

import java.util.Objects;

/** Options that control compilation behavior and artifact output. */
public final class CompileOptions {
  private final ArtifactKind artifactKind;
  private final RuleSelectionPolicy ruleSelectionPolicy;
  private final String priorityColumn;

  private CompileOptions(
      ArtifactKind artifactKind, RuleSelectionPolicy ruleSelectionPolicy, String priorityColumn) {
    this.artifactKind = Objects.requireNonNull(artifactKind, "artifactKind");
    this.ruleSelectionPolicy = Objects.requireNonNull(ruleSelectionPolicy, "ruleSelectionPolicy");
    this.priorityColumn = Objects.requireNonNull(priorityColumn, "priorityColumn");
  }

  public static CompileOptions production() {
    return new CompileOptions(ArtifactKind.PRODUCTION, RuleSelectionPolicy.AUTO, "PRIORITY");
  }

  public static CompileOptions testInclusive() {
    return new CompileOptions(ArtifactKind.TEST_INCLUSIVE, RuleSelectionPolicy.AUTO, "PRIORITY");
  }

  public CompileOptions withArtifactKind(ArtifactKind artifactKind) {
    return new CompileOptions(artifactKind, ruleSelectionPolicy, priorityColumn);
  }

  public CompileOptions withRuleSelection(RuleSelectionPolicy ruleSelectionPolicy) {
    return new CompileOptions(artifactKind, ruleSelectionPolicy, priorityColumn);
  }

  public CompileOptions withPriorityColumn(String priorityColumn) {
    return new CompileOptions(artifactKind, ruleSelectionPolicy, priorityColumn);
  }

  public ArtifactKind artifactKind() {
    return artifactKind;
  }

  public RuleSelectionPolicy ruleSelectionPolicy() {
    return ruleSelectionPolicy;
  }

  public String priorityColumn() {
    return priorityColumn;
  }
}
