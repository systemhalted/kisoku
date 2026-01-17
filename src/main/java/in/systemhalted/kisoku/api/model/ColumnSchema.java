package in.systemhalted.kisoku.api.model;

import java.util.Objects;

/** Schema definition for a single column in a decision table. */
public final class ColumnSchema {
  private final String name;
  private final ColumnType type;
  private final boolean nullable;

  private ColumnSchema(String name, ColumnType type, boolean nullable) {
    this.name = Objects.requireNonNull(name, "name");
    this.type = Objects.requireNonNull(type, "type");
    this.nullable = nullable;
  }

  public static ColumnSchema of(String name, ColumnType type) {
    return new ColumnSchema(name, type, true);
  }

  public static ColumnSchema of(String name, ColumnType type, boolean nullable) {
    return new ColumnSchema(name, type, nullable);
  }

  public String name() {
    return name;
  }

  public ColumnType type() {
    return type;
  }

  public boolean nullable() {
    return nullable;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!(obj instanceof ColumnSchema other)) return false;
    return name.equals(other.name) && type == other.type && nullable == other.nullable;
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, type, nullable);
  }

  @Override
  public String toString() {
    return name + ":" + type + (nullable ? "" : " NOT NULL");
  }
}
