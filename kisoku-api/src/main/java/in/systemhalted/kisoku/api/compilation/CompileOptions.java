package in.systemhalted.kisoku.api.compilation;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import java.util.Objects;

/** Options that control compilation behavior and artifact output. */
public final class CompileOptions {
  private final ArtifactKind artifactKind;
  private final RuleSelectionPolicy ruleSelectionPolicy;
  private final String priorityColumn;
  private final Schema schema;

  private CompileOptions(
      ArtifactKind artifactKind,
      RuleSelectionPolicy ruleSelectionPolicy,
      String priorityColumn,
      Schema schema) {
    this.artifactKind = Objects.requireNonNull(artifactKind, "artifactKind");
    this.ruleSelectionPolicy = Objects.requireNonNull(ruleSelectionPolicy, "ruleSelectionPolicy");
    this.priorityColumn = Objects.requireNonNull(priorityColumn, "priorityColumn");
    this.schema = Objects.requireNonNull(schema, "schema");
  }

  public static CompileOptions production(Schema schema) {
    return new CompileOptions(
        ArtifactKind.PRODUCTION, RuleSelectionPolicy.AUTO, "PRIORITY", schema);
  }

  public static CompileOptions testInclusive(Schema schema) {
    return new CompileOptions(
        ArtifactKind.TEST_INCLUSIVE, RuleSelectionPolicy.AUTO, "PRIORITY", schema);
  }

  public CompileOptions withArtifactKind(ArtifactKind artifactKind) {
    return new CompileOptions(artifactKind, ruleSelectionPolicy, priorityColumn, schema);
  }

  public CompileOptions withRuleSelection(RuleSelectionPolicy ruleSelectionPolicy) {
    return new CompileOptions(artifactKind, ruleSelectionPolicy, priorityColumn, schema);
  }

  public CompileOptions withPriorityColumn(String priorityColumn) {
    return new CompileOptions(artifactKind, ruleSelectionPolicy, priorityColumn, schema);
  }

  public CompileOptions withSchema(Schema schema) {
    return new CompileOptions(artifactKind, ruleSelectionPolicy, priorityColumn, schema);
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

  public Schema schema() {
    return schema;
  }
}
