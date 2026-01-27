package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import in.systemhalted.kisoku.runtime.loader.index.ComparisonIndex;
import in.systemhalted.kisoku.runtime.loader.index.EqualityIndex;

/**
 * Factory for building column indexes based on operator type.
 *
 * <p>Supported operators:
 *
 * <ul>
 *   <li>EQ - via {@link EqualityIndex} (hash-based)
 *   <li>GT, GTE, LT, LTE - via {@link ComparisonIndex} (sorted array with binary search)
 * </ul>
 *
 * <p>Future phases will add BETWEEN, IN, and other operators.
 */
final class ColumnIndexBuilder {
  private ColumnIndexBuilder() {}

  /**
   * Build an appropriate index for the given column, or null if indexing is not supported.
   *
   * @param decoder the column decoder
   * @param column the column definition
   * @param rowCount total number of rows
   * @return the built index, or null if no index available for this operator
   */
  static ColumnIndex build(ColumnDecoder decoder, ColumnDefinition column, int rowCount) {
    // Skip non-input columns (outputs, metadata)
    if (!column.isInput()) {
      return null;
    }

    // Skip TEST_ columns
    if (column.isTestOnly()) {
      return null;
    }

    Operator op = column.operator();

    // Phase 1: Only support EQ operator
    return switch (decoder) {
      case ScalarColumnDecoder scalarDecoder ->
          switch (op) {
            case EQ ->
                EqualityIndex.build(
                    scalarDecoder.values(), scalarDecoder.presenceBitmap(), rowCount);
            case GT, GTE, LT, LTE ->
                ComparisonIndex.build(
                    scalarDecoder.values(), scalarDecoder.presenceBitmap(), op, rowCount);
            case null, default ->

                // Future phases will add:
                // - RangeIntervalIndex for BETWEEN_*, NOT_BETWEEN_*
                // - SetMembershipIndex for IN, NOT_IN

                null;
          };

      default -> null;
    };
  }
}
