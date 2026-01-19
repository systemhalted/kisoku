package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import in.systemhalted.kisoku.runtime.loader.index.EqualityIndex;

/**
 * Factory for building column indexes based on operator type.
 *
 * <p>Phase 1 supports only EQ operator via {@link EqualityIndex}. Other operators (GT, LT, BETWEEN,
 * IN, etc.) will be added in Phase 2.
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
    if (op == Operator.EQ && decoder instanceof ScalarColumnDecoder scalarDecoder) {
      return EqualityIndex.build(scalarDecoder.values(), scalarDecoder.presenceBitmap(), rowCount);
    }

    // Phase 2 will add:
    // - ComparisonIndex for GT, GTE, LT, LTE
    // - RangeIntervalIndex for BETWEEN_*, NOT_BETWEEN_*
    // - SetMembershipIndex for IN, NOT_IN

    return null;
  }
}
