package in.systemhalted.kisoku.runtime.loader.index;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.util.HashMap;
import java.util.Map;

/**
 * Inverted index for IN and NOT_IN operator columns.
 *
 * <p>Maps each unique value to a bitmap of rows whose set contains that value. This enables O(1)
 * lookup for any input value. Rows with blank cells (no condition) are tracked separately and
 * included in all candidate results since blanks match any input.
 *
 * <p>For NOT_IN operations, we also track all rows that have conditions (non-blank) to compute the
 * complement efficiently.
 *
 * <p>Memory usage: ~1 MB for 5M rows with 1000 unique values across all sets (1000 bitmaps * 625 KB
 * / 64 shared structure).
 */
public final class SetMembershipIndex implements ColumnIndex {
  private final Map<Integer, long[]> valueToRowBitmap;
  private final long[] noConditionRows;
  private final long[] allConditionRows;
  private final Operator operator;
  private final int rowCount;

  private SetMembershipIndex(
      Map<Integer, long[]> valueToRowBitmap,
      long[] noConditionRows,
      long[] allConditionRows,
      Operator operator,
      int rowCount) {
    this.valueToRowBitmap = Map.copyOf(valueToRowBitmap);
    this.noConditionRows = noConditionRows;
    this.allConditionRows = allConditionRows;
    this.operator = operator;
    this.rowCount = rowCount;
  }

  /**
   * Build a set membership index from column data.
   *
   * @param listOffsets array of offsets into allValues for each row's set
   * @param listLengths array of lengths for each row's set (unsigned shorts)
   * @param allValues the packed array containing all set values
   * @param presenceBitmap the presence bitmap (MSB-first, byte array)
   * @param operator the operator (IN or NOT_IN)
   * @param rowCount total number of rows
   * @return the built index
   */
  public static SetMembershipIndex build(
      int[] listOffsets,
      short[] listLengths,
      int[] allValues,
      byte[] presenceBitmap,
      Operator operator,
      int rowCount) {
    int longCount = CandidateBitmap.longCount(rowCount);

    // Track rows with no condition (blank cells)
    long[] noConditionRows = new long[longCount];

    // Track all rows with conditions (for NOT_IN complement)
    long[] allConditionRows = new long[longCount];

    // Map each unique value to rows containing it
    Map<Integer, long[]> valueToRows = new HashMap<>();

    for (int row = 0; row < rowCount; row++) {
      if (isPresent(presenceBitmap, row)) {
        // Row has a condition - mark in allConditionRows
        CandidateBitmap.set(allConditionRows, row);

        // Add row to bitmap for each value in its set
        int offset = listOffsets[row];
        int length = listLengths[row] & 0xFFFF; // Convert signed short to unsigned

        for (int i = 0; i < length; i++) {
          int value = allValues[offset + i];
          long[] bitmap = valueToRows.computeIfAbsent(value, k -> new long[longCount]);
          CandidateBitmap.set(bitmap, row);
        }
      } else {
        // Row has no condition (blank) - always matches
        CandidateBitmap.set(noConditionRows, row);
      }
    }

    return new SetMembershipIndex(
        valueToRows, noConditionRows, allConditionRows, operator, rowCount);
  }

  /**
   * {@inheritDoc}
   *
   * <p>For IN operator: returns rows where the input value is in the row's set, plus blank rows.
   *
   * <p>For NOT_IN operator: returns rows where the input value is NOT in the row's set, plus blank
   * rows. This is computed as (allConditionRows ANDNOT valueMatch) OR noConditionRows.
   */
  @Override
  public long[] getCandidates(int inputValue) {
    long[] valueMatch = valueToRowBitmap.get(inputValue);

    if (operator == Operator.IN) {
      // IN: return rows containing inputValue OR blank rows
      if (valueMatch == null) {
        // No rows contain this value - only blank rows match
        return CandidateBitmap.copy(noConditionRows);
      }
      return CandidateBitmap.or(valueMatch, noConditionRows);
    } else {
      // NOT_IN: return rows NOT containing inputValue OR blank rows
      if (valueMatch == null) {
        // Value not in any set - all condition rows match (plus blanks)
        return CandidateBitmap.or(allConditionRows, noConditionRows);
      }
      // Rows with conditions that don't contain the value, plus blanks
      long[] notContaining = CandidateBitmap.andNot(allConditionRows, valueMatch);
      return CandidateBitmap.or(notContaining, noConditionRows);
    }
  }

  @Override
  public long memorySizeBytes() {
    int longCount = CandidateBitmap.longCount(rowCount);
    long bytesPerBitmap = longCount * 8L;

    // noConditionRows bitmap
    long size = bytesPerBitmap;

    // allConditionRows bitmap
    size += bytesPerBitmap;

    // All value bitmaps
    size += valueToRowBitmap.size() * bytesPerBitmap;

    // HashMap overhead (~48 bytes per entry for key + value reference)
    size += valueToRowBitmap.size() * 48L;

    return size;
  }

  /**
   * Get the number of unique values indexed across all sets.
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
