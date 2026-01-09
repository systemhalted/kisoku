package in.systemhalted.kisoku.api.model;

import java.util.List;
import java.util.Optional;

/** Metadata describing a compiled or loaded ruleset. */
public final class RulesetMetadata {
  private final long rowCount;
  private final List<String> inputColumns;
  private final List<String> outputColumns;
  private final String priorityColumn;
  private final ArtifactKind artifactKind;

  public RulesetMetadata(
      long rowCount,
      List<String> inputColumns,
      List<String> outputColumns,
      String priorityColumn,
      ArtifactKind artifactKind) {
    this.rowCount = rowCount;
    this.inputColumns = List.copyOf(inputColumns);
    this.outputColumns = List.copyOf(outputColumns);
    this.priorityColumn = priorityColumn;
    this.artifactKind = artifactKind;
  }

  public long rowCount() {
    return rowCount;
  }

  public int inputColumnCount() {
    return inputColumns.size();
  }

  public int outputColumnCount() {
    return outputColumns.size();
  }

  public List<String> inputColumns() {
    return inputColumns;
  }

  public List<String> outputColumns() {
    return outputColumns;
  }

  public Optional<String> priorityColumn() {
    return Optional.ofNullable(priorityColumn);
  }

  public ArtifactKind artifactKind() {
    return artifactKind;
  }
}
