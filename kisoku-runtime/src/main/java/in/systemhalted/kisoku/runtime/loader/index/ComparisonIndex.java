package in.systemhalted.kisoku.runtime.loader.index;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.util.TreeMap;

/**
 * Sorted array index for comparison operator columns (GT, GTE, LT, LTE).
 *
 * <p>Stores unique column thresholds in sorted order, each mapped to a bitmap of rows containing
 * that threshold. Uses binary search to find the boundary index, then OR-combines bitmaps for all
 * rows whose rule condition is satisfied by the input. Rows with blank cells (no condition) are
 * tracked separately and included in all candidate results since blanks match any input.
 *
 * <p>Decision table semantics - for a rule with threshold T and input value V:
 *
 * <ul>
 *   <li>GT: rule matches when V > T (return rows with thresholds below input)
 *   <li>GTE: rule matches when V >= T (return rows with thresholds at or below input)
 *   <li>LT: rule matches when V < T (return rows with thresholds above input)
 *   <li>LTE: rule matches when V <= T (return rows with thresholds at or above input)
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

  /**
   * Build a ComparisonIndex from raw column data.
   *
   * @param values the values array from ScalarColumnDecoder
   * @param presenceBitmap the presence bitmap (MSB-first encoding)
   * @param operator the comparison operator (GT, GTE, LT, LTE)
   * @param rowCount total number of rows
   * @return the built index
   */
  public static ComparisonIndex build(
      int[] values, byte[] presenceBitmap, Operator operator, int rowCount) {
    int longCount = CandidateBitmap.longCount(rowCount);

    // Track rows with no condition (blank cells)
    long[] noConditionRows = new long[longCount];

    // Group rows by value (TreeMap maintains sorted order)
    var valueToRows = new TreeMap<Integer, long[]>();

    for (int row = 0; row < rowCount; row++) {
      if (isPresent(presenceBitmap, row)) {
        // Row has a condition - add to value's bitmap
        int value = values[row];
        long[] bitmap = valueToRows.computeIfAbsent(value, k -> new long[longCount]);
        CandidateBitmap.set(bitmap, row);
      } else {
        CandidateBitmap.set(noConditionRows, row);
      }
    }

    // 5. Convert TreeMap to sorted arrays (sortedValues and rowBitmaps)
    int uniqueCount = valueToRows.size();
    int[] sortedValues = new int[uniqueCount];
    long[][] rowBitmaps = new long[uniqueCount][];

    int i = 0;

    for (var entry : valueToRows.entrySet()) {
      sortedValues[i] = entry.getKey();
      rowBitmaps[i] = entry.getValue();
      i++;
    }

    // 6. Return new ComparisonIndex(sortedValues, rowBitmaps, operator, blankRowBitmap)
    return new ComparisonIndex(sortedValues, rowBitmaps, operator, noConditionRows);
    // throw new UnsupportedOperationException("Not yet implemented");
  }

  /**
   * Check if bit at rowIndex is set in presence bitmap (MSB-first, matching decoder).
   *
   * <p>This duplicates BitMapUtils.isPresent to avoid package dependency.
   */
  private static boolean isPresent(byte[] bitmap, int rowIndex) {
    int byteIndex = rowIndex / 8;
    int bitIndex = 7 - (rowIndex % 8); // MSB-first
    return (bitmap[byteIndex] & (1 << bitIndex)) != 0;
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
   * rows whose rule condition is satisfied by the input. Time complexity is O(log n + k * b) where
   * n is unique values, k is matching values, and b is bitmap length.
   *
   * <p>Decision table semantics - for a rule with threshold T and input value V:
   *
   * <ul>
   *   <li>GT: rule matches when V > T → return rows where T < V (thresholds below input)
   *   <li>GTE: rule matches when V >= T → return rows where T <= V (thresholds at or below input)
   *   <li>LT: rule matches when V < T → return rows where T > V (thresholds above input)
   *   <li>LTE: rule matches when V <= T → return rows where T >= V (thresholds at or above input)
   * </ul>
   */
  @Override
  public long[] getCandidates(int inputValue) {
    int idx = lowerBound(sortedValues, inputValue);

    int start = 0;
    int endExclusive = sortedValues.length;

    // GT: rule "AGE GT 18" matches when input > 18
    //     → need thresholds BELOW input (values < inputValue)
    //     → set endExclusive = idx (same as current LT)
    //
    // GTE: rule "AGE GTE 18" matches when input >= 18
    //     → need thresholds AT OR BELOW input (values <= inputValue)
    //     → set endExclusive, include equal (same as current LTE)
    //
    // LT: rule "AGE LT 65" matches when input < 65
    //     → need thresholds ABOVE input (values > inputValue)
    //     → set start = idx, skip equal (same as current GT)
    //
    // LTE: rule "AGE LTE 65" matches when input <= 65
    //     → need thresholds AT OR ABOVE input (values >= inputValue)
    //     → set start = idx (same as current GTE)
    switch (operator) {
      case LT -> {
        start = idx;
        if (start < sortedValues.length && sortedValues[start] == inputValue) {
          start++;
        }
      }

      case LTE -> start = idx;

      case GT -> endExclusive = idx;

      case GTE -> {
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
