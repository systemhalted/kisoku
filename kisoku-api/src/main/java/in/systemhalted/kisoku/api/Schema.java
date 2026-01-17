package in.systemhalted.kisoku.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * External schema defining column types for a decision table.
 *
 * <p>The schema must declare types for all non-reserved columns. Reserved columns (RULE_ID,
 * PRIORITY) have implicit types and do not need to be declared.
 */
public final class Schema {
  private final Map<String, ColumnSchema> columns;

  private Schema(Map<String, ColumnSchema> columns) {
    this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Returns the schema for the given column name, if declared. */
  public Optional<ColumnSchema> column(String name) {
    return Optional.ofNullable(columns.get(name));
  }

  /** Returns all declared column schemas in declaration order. */
  public List<ColumnSchema> columns() {
    return new ArrayList<>(columns.values());
  }

  /** Returns true if a column with the given name is declared. */
  public boolean hasColumn(String name) {
    return columns.containsKey(name);
  }

  /** Returns the number of declared columns. */
  public int size() {
    return columns.size();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof Schema other)) return false;
    return columns.equals(other.columns);
  }

  @Override
  public int hashCode() {
    return columns.hashCode();
  }

  public static final class Builder {
    private final Map<String, ColumnSchema> columns = new LinkedHashMap<>();

    private Builder() {}

    public Builder column(String name, ColumnType type) {
      columns.put(Objects.requireNonNull(name, "name"), ColumnSchema.of(name, type));
      return this;
    }

    public Builder column(String name, ColumnType type, boolean nullable) {
      columns.put(Objects.requireNonNull(name, "name"), ColumnSchema.of(name, type, nullable));
      return this;
    }

    public Builder column(ColumnSchema columnSchema) {
      columns.put(columnSchema.name(), columnSchema);
      return this;
    }

    public Schema build() {
      return new Schema(columns);
    }
  }
}
