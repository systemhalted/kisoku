package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.PostingListIndex;
import in.systemhalted.kisoku.runtime.loader.index.PostingListIndexBuilder;

/**
 * Builds per-column posting-list indexes from decoded column data.
 *
 * <p>Indexable operators:
 *
 * <ul>
 *   <li>{@code EQ}, {@code GT}, {@code GTE}, {@code LT}, {@code LTE}, {@code NE} - scalar columns
 *   <li>{@code IN}, {@code NOT_IN} - set-membership columns (one posting per set member)
 * </ul>
 *
 * <p>Negative operators are indexed for exact candidate <em>counts</em> only; their match sets are
 * complements and are never enumerated. Range operators ({@code BETWEEN_*}) are not indexed and
 * fall back to verification.
 *
 * <p>The decoder accessors materialize each column's raw data on the heap transiently, one column
 * at a time; the finished index lives in a direct buffer off the heap.
 */
final class ColumnIndexBuilder {
  private ColumnIndexBuilder() {}

  /**
   * Build an index for the given column, or null if the column is not indexable.
   *
   * @param decoder the column decoder
   * @param column the column definition
   * @param rowCount total number of rows
   * @return the built index, or null for non-input, test-only, or unsupported-operator columns
   */
  static PostingListIndex build(ColumnDecoder decoder, ColumnDefinition column, int rowCount) {
    if (!column.isInput() || column.isTestOnly()) {
      return null;
    }

    Operator op = column.operator();

    return switch (decoder) {
      case ScalarColumnDecoder scalar ->
          switch (op) {
            case EQ, NE, GT, GTE, LT, LTE -> buildScalar(scalar, op, rowCount);
            case null, default -> null;
          };
      case SetMembershipColumnDecoder sets ->
          switch (op) {
            case IN, NOT_IN -> buildSetMembership(sets, op, rowCount);
            case null, default -> null;
          };
      default -> null;
    };
  }

  private static PostingListIndex buildScalar(
      ScalarColumnDecoder decoder, Operator op, int rowCount) {
    long[] values = decoder.values();
    byte[] presence = decoder.presenceBitmap();

    int conditionRows = 0;
    for (int row = 0; row < rowCount; row++) {
      if (BitMapUtils.isPresent(presence, row)) {
        conditionRows++;
      }
    }

    PostingListIndexBuilder builder = new PostingListIndexBuilder(op, conditionRows, conditionRows);
    for (int row = 0; row < rowCount; row++) {
      if (BitMapUtils.isPresent(presence, row)) {
        builder.addPair(values[row], row);
      } else {
        builder.addBlank(row);
      }
    }
    return builder.build();
  }

  private static PostingListIndex buildSetMembership(
      SetMembershipColumnDecoder decoder, Operator op, int rowCount) {
    int[] offsets = decoder.listOffsets();
    short[] lengths = decoder.listLengths();
    long[] allValues = decoder.allValues();
    byte[] presence = decoder.presenceBitmap();

    int conditionRows = 0;
    for (int row = 0; row < rowCount; row++) {
      if (BitMapUtils.isPresent(presence, row)) {
        conditionRows++;
      }
    }

    PostingListIndexBuilder builder =
        new PostingListIndexBuilder(op, conditionRows, allValues.length);
    for (int row = 0; row < rowCount; row++) {
      if (BitMapUtils.isPresent(presence, row)) {
        int offset = offsets[row];
        int length = lengths[row] & 0xFFFF;
        for (int i = 0; i < length; i++) {
          builder.addPair(allValues[offset + i], row);
        }
      } else {
        builder.addBlank(row);
      }
    }
    return builder.build();
  }
}
