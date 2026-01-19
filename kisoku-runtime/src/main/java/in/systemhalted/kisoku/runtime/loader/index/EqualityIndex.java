package in.systemhalted.kisoku.runtime.loader.index;

import java.util.HashMap;
import java.util.Map;

/**
 * Hash-based index for EQ operator columns.
 *
 * <p>Maps each unique value to a bitmap of rows containing that value. Rows with blank cells (no
 * condition) are tracked separately and included in all candidate results since blanks match any
 * input.
 *
 * <p>Memory usage: ~1 MB for 5M rows with 1000 unique values (1000 bitmaps * 625 KB / 64 shared
 * structure).
 */
public final class EqualityIndex implements ColumnIndex {
  private final Map<Integer, long[]> valueToRowBitmap;
  private final long[] noConditionRows;
  private final int rowCount;

  private EqualityIndex(
      Map<Integer, long[]> valueToRowBitmap, long[] noConditionRows, int rowCount) {
    this.valueToRowBitmap = Map.copyOf(valueToRowBitmap);
    this.noConditionRows = noConditionRows;
    this.rowCount = rowCount;
  }

  /**
   * Build an equality index from column data.
   *
   * @param values the column values (one per row)
   * @param presenceBitmap the presence bitmap (MSB-first, byte array)
   * @param rowCount total number of rows
   * @return the built index
   */
  public static EqualityIndex build(int[] values, byte[] presenceBitmap, int rowCount) {
    int longCount = CandidateBitmap.longCount(rowCount);

    // Track rows with no condition (blank cells)
    long[] noConditionRows = new long[longCount];

    // Group rows by value
    Map<Integer, long[]> valueToRows = new HashMap<>();

    for (int row = 0; row < rowCount; row++) {
      if (isPresent(presenceBitmap, row)) {
        // Row has a condition - add to value's bitmap
        int value = values[row];
        long[] bitmap = valueToRows.computeIfAbsent(value, k -> new long[longCount]);
        CandidateBitmap.set(bitmap, row);
      } else {
        // Row has no condition (blank) - always matches
        CandidateBitmap.set(noConditionRows, row);
      }
    }

    return new EqualityIndex(valueToRows, noConditionRows, rowCount);
  }

  @Override
  public long[] getCandidates(int inputValue) {
    long[] exactMatch = valueToRowBitmap.get(inputValue);

    if (exactMatch == null) {
      // No rows have this exact value - only blank rows match
      return CandidateBitmap.copy(noConditionRows);
    }

    // Return rows with exact match OR rows with no condition (blank)
    return CandidateBitmap.or(exactMatch, noConditionRows);
  }

  @Override
  public long memorySizeBytes() {
    int longCount = CandidateBitmap.longCount(rowCount);
    long bytesPerBitmap = longCount * 8L;

    // noConditionRows bitmap
    long size = bytesPerBitmap;

    // All value bitmaps
    size += valueToRowBitmap.size() * bytesPerBitmap;

    // HashMap overhead (~48 bytes per entry for key + value reference)
    size += valueToRowBitmap.size() * 48L;

    return size;
  }

  /**
   * Get the number of unique values indexed.
   *
   * @return count of distinct values
   */
  public int uniqueValueCount() {
    return valueToRowBitmap.size();
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
}
