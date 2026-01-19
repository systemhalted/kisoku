package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Base interface for decoding column data and matching input values.
 *
 * <p>Implementations handle different operator types with their specific data formats.
 */
sealed interface ColumnDecoder
    permits ScalarColumnDecoder, RangeColumnDecoder, SetMembershipColumnDecoder {

  /**
   * Check if the input value matches the condition at the given row.
   *
   * @param rowIndex the row to check
   * @param inputValue the value from DecisionInput (may be null)
   * @return true if matches, false otherwise
   */
  boolean matches(int rowIndex, Object inputValue);

  /**
   * Check if this row has a condition (not blank).
   *
   * @param rowIndex the row to check
   * @return true if the row has a condition
   */
  boolean hasCondition(int rowIndex);

  /**
   * Get the output value for this row (for output columns).
   *
   * @param rowIndex the row index
   * @return the decoded value
   */
  Object getValue(int rowIndex);

  /**
   * Factory method to create the appropriate decoder for a column.
   *
   * @param column the column definition
   * @param data ByteBuffer positioned at the column's data
   * @param rowCount number of rows
   * @param dictionary the string dictionary
   * @return the appropriate decoder
   */
  static ColumnDecoder create(
      ColumnDefinition column, ByteBuffer data, int rowCount, StringDictionaryReader dictionary) {
    Operator op = column.operator();
    return switch (op) {
      case RULE_ID, PRIORITY, SET, EQ, NE, GT, GTE, LT, LTE ->
          ScalarColumnDecoder.create(column, data, rowCount, dictionary);
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE ->
          RangeColumnDecoder.create(column, data, rowCount, dictionary);
      case IN, NOT_IN -> SetMembershipColumnDecoder.create(column, data, rowCount, dictionary);
    };
  }
}
