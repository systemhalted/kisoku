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
   * <p>Missing-input semantics: a blank cell carries no condition and matches anything, but a
   * non-blank condition can never be satisfied by an absent input. A {@code null} {@code
   * inputValue} therefore fails every row that has a condition, including negative operators such
   * as {@code NE} and {@code NOT_IN} — "unknown" is not evidence that a value differs.
   *
   * @param rowIndex the row to check
   * @param inputValue the value from DecisionInput, or null when the field is absent
   * @return true if matches, false otherwise
   */
  boolean matches(int rowIndex, Object inputValue);

  /**
   * Check if an already-coerced input value matches the condition at the given row.
   *
   * <p>Equivalent to {@link #matches(int, Object)} but takes the comparable int produced by {@link
   * TypeCoercion#toComparableInt} directly, skipping per-call coercion. The columnar bulk kernel
   * uses this to verify survivors from a batch of pre-coerced codes without re-coercing.
   *
   * <p>{@code present} must be carried alongside the code because the coerced domain cannot express
   * absence: an absent field and a present-but-unknown string both coerce to {@code NULL_ID}, yet
   * only the latter may satisfy a condition.
   *
   * @param rowIndex the row to check
   * @param coercedValue the input value already coerced to its comparable int
   * @param present whether the input actually supplied a value for this column
   * @return true if matches, false otherwise
   */
  boolean matchesCoerced(int rowIndex, int coercedValue, boolean present);

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
   * @param buffer the artifact buffer (read via absolute offsets)
   * @param base absolute byte offset of this column's data within the buffer
   * @param rowCount number of rows
   * @param dictionary the string dictionary
   * @return the appropriate decoder
   */
  static ColumnDecoder create(
      ColumnDefinition column,
      ByteBuffer buffer,
      int base,
      int rowCount,
      StringDictionaryReader dictionary) {
    Operator op = column.operator();
    return switch (op) {
      case RULE_ID, PRIORITY, SET, EQ, NE, GT, GTE, LT, LTE ->
          ScalarColumnDecoder.create(column, buffer, base, rowCount, dictionary);
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE ->
          RangeColumnDecoder.create(column, buffer, base, rowCount, dictionary);
      case IN, NOT_IN ->
          SetMembershipColumnDecoder.create(column, buffer, base, rowCount, dictionary);
    };
  }
}
