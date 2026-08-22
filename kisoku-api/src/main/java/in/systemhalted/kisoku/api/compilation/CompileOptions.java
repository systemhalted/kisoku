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
  private final boolean persistIndexes;

  private CompileOptions(
      ArtifactKind artifactKind,
      RuleSelectionPolicy ruleSelectionPolicy,
      String priorityColumn,
      Schema schema,
      boolean persistIndexes) {
    this.artifactKind = Objects.requireNonNull(artifactKind, "artifactKind");
    this.ruleSelectionPolicy = Objects.requireNonNull(ruleSelectionPolicy, "ruleSelectionPolicy");
    this.priorityColumn = Objects.requireNonNull(priorityColumn, "priorityColumn");
    this.schema = Objects.requireNonNull(schema, "schema");
    this.persistIndexes = persistIndexes;
  }

  public static CompileOptions production(Schema schema) {
    return new CompileOptions(
        ArtifactKind.PRODUCTION, RuleSelectionPolicy.AUTO, "PRIORITY", schema, true);
  }

  public static CompileOptions testInclusive(Schema schema) {
    return new CompileOptions(
        ArtifactKind.TEST_INCLUSIVE, RuleSelectionPolicy.AUTO, "PRIORITY", schema, true);
  }

  public CompileOptions withArtifactKind(ArtifactKind artifactKind) {
    return new CompileOptions(
        artifactKind, ruleSelectionPolicy, priorityColumn, schema, persistIndexes);
  }

  public CompileOptions withRuleSelection(RuleSelectionPolicy ruleSelectionPolicy) {
    return new CompileOptions(
        artifactKind, ruleSelectionPolicy, priorityColumn, schema, persistIndexes);
  }

  public CompileOptions withPriorityColumn(String priorityColumn) {
    return new CompileOptions(
        artifactKind, ruleSelectionPolicy, priorityColumn, schema, persistIndexes);
  }

  public CompileOptions withSchema(Schema schema) {
    return new CompileOptions(
        artifactKind, ruleSelectionPolicy, priorityColumn, schema, persistIndexes);
  }

  /**
   * Whether the compiler builds the per-column candidate indexes and persists them into the
   * artifact (the default). Persisted indexes are memory-mapped at load, so loading skips the index
   * build entirely; disabling trades a smaller artifact and a faster compile for that build cost on
   * every load.
   *
   * @param persistIndexes whether to embed indexes in the artifact
   * @return a copy of these options with the setting applied
   */
  public CompileOptions withPersistIndexes(boolean persistIndexes) {
    return new CompileOptions(
        artifactKind, ruleSelectionPolicy, priorityColumn, schema, persistIndexes);
  }

  public boolean isPersistIndexes() {
    return persistIndexes;
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
