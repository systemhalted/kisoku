package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.runtime.csv.Operator;

/**
 * Parsed column metadata from the binary artifact.
 *
 * @param nameId dictionary ID of the column name
 * @param name resolved column name
 * @param operator the column operator
 * @param type the column data type
 * @param role the column role (INPUT, OUTPUT, METADATA)
 * @param flags bit flags (0x01=nullable, 0x02=test-only)
 * @param dataOffset byte offset within the rule data section
 */
record ColumnDefinition(
    int nameId,
    String name,
    Operator operator,
    ColumnType type,
    ColumnRole role,
    int flags,
    int dataOffset) {

  /** Flag indicating the column allows null values. */
  static final int FLAG_NULLABLE = 0x01;

  /** Flag indicating this is a test-only column (TEST_ prefix). */
  static final int FLAG_TEST_ONLY = 0x02;

  /** Returns true if this column is test-only. */
  boolean isTestOnly() {
    return (flags & FLAG_TEST_ONLY) != 0;
  }

  /** Returns true if this column allows null values. */
  boolean isNullable() {
    return (flags & FLAG_NULLABLE) != 0;
  }

  /** Returns true if this is an input column. */
  boolean isInput() {
    return role == ColumnRole.INPUT;
  }

  /** Returns true if this is an output column. */
  boolean isOutput() {
    return role == ColumnRole.OUTPUT;
  }

  /** Returns true if this is a metadata column (RULE_ID or PRIORITY). */
  boolean isMetadata() {
    return role == ColumnRole.METADATA;
  }
}
