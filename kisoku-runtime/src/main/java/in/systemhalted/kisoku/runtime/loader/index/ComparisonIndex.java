package in.systemhalted.kisoku.runtime.loader.index;

import in.systemhalted.kisoku.runtime.csv.Operator;

/**
 * Sorted array index for comparison operator columns (GT, GTE, LT, LTE).
 *
 * <p>Stores unique column values in sorted order, each mapped to a bitmap of rows containing that
 * value. Uses binary search to find the boundary index, then OR-combines bitmaps for all matching
 * values based on operator semantics. Rows with blank cells (no condition) are tracked separately
 * and included in all candidate results since blanks match any input.
 *
 * <p>Operator semantics for input value V:
 *
 * <ul>
 *   <li>GT: matches rows where column value > V (values after V in sorted order)
 *   <li>GTE: matches rows where column value >= V (values at or after V)
 *   <li>LT: matches rows where column value < V (values before V)
 *   <li>LTE: matches rows where column value <= V (values at or before V)
 * </ul>
 *
 * <p>This index is immutable and thread-safe after construction.
 */
public final class ComparisonIndex implements ColumnIndex {

  private final int[] sortedValues;
  private final long[][] rowBitmaps;
  private final Operator operator;
  private final long[] blankRowBitmap;

  /**
   * Constructs a comparison index with defensive copies of all array parameters.
   *
   * @param sortedValues unique column values in ascending sorted order
   * @param rowBitmaps bitmap for each value; rowBitmaps[i] contains rows with sortedValues[i]
   * @param operator the comparison operator (GT, GTE, LT, or LTE)
   * @param blankRowBitmap bitmap of rows with blank cells (always match any input)
   */
  public ComparisonIndex(
      int[] sortedValues, long[][] rowBitmaps, Operator operator, long[] blankRowBitmap) {
    this.sortedValues = sortedValues.clone();
    this.rowBitmaps = deepClone(rowBitmaps);
    this.operator = operator;
    this.blankRowBitmap = blankRowBitmap.clone();
  }

  /** Creates a deep copy of a 2D long array. */
  private static long[][] deepClone(long[][] original) {
    long[][] copy = new long[original.length][];
    for (int i = 0; i < original.length; i++) {
      copy[i] = original[i].clone();
    }
    return copy;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Uses binary search to locate the input value's position, then OR-combines bitmaps for all
   * values that satisfy the operator. Time complexity is O(log n + k * b) where n is unique values,
   * k is matching values, and b is bitmap length.
   */
  @Override
  public long[] getCandidates(int inputValue) {
    int idx = lowerBound(sortedValues, inputValue);

    int start = 0;
    int endExclusive = sortedValues.length;

    switch (operator) {
      case GT -> {
        start = idx;
        if (start < sortedValues.length && sortedValues[start] == inputValue) {
          start++;
        }
      }

      case GTE -> start = idx;

      case LT -> endExclusive = idx;

      case LTE -> {
        endExclusive = idx;
        if (endExclusive < sortedValues.length && sortedValues[endExclusive] == inputValue) {
          endExclusive++;
        }
      }

      default -> throw new IllegalStateException("Unsupported operator: " + operator);
    }

    // Always include blank rows
    long[] result = blankRowBitmap.clone();

    if (start >= endExclusive) {
      return result;
    }

    for (int i = start; i < endExclusive; i++) {
      long[] bitmap = rowBitmaps[i];
      for (int j = 0; j < result.length; j++) {
        result[j] |= bitmap[j];
      }
    }
    return result;
  }

  @Override
  public long memorySizeBytes() {
    // size of sortedValues
    long sizeOfSortedValues = sortedValues.length * 4L;
    // size of rowBitmaps
    long sizeOfRowBitmaps =
        rowBitmaps.length == 0 ? 0 : rowBitmaps.length * rowBitmaps[0].length * 8L;
    // size of blankRowBitmaps
    long sizeOfBlankRowBitmaps = blankRowBitmap.length * 8L;

    return sizeOfSortedValues + sizeOfRowBitmaps + sizeOfBlankRowBitmaps;
  }

  /**
   * Finds the lower bound index for a key in a sorted array.
   *
   * <p>Returns the index of the first element >= key, or values.length if all elements are less
   * than key. Equivalent to C++ {@code std::lower_bound}.
   *
   * @param values sorted array to search
   * @param key the value to find the lower bound for
   * @return index of first element >= key, or values.length if none exists
   */
  private static int lowerBound(int[] values, int key) {
    int lo = 0;
    int hi = values.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (values[mid] < key) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
