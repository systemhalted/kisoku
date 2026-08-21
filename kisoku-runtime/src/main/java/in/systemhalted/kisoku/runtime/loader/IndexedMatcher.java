package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.loader.index.PostingListIndex;
import java.util.List;

/**
 * Finds the winning rule row for one coerced input, shared by single evaluation and the bulk
 * kernel.
 *
 * <p>Strategy: ask every indexed input column for its exact candidate count (an O(log distinct)
 * offset subtraction on the posting-list index), then drive matching from the single most selective
 * enumerable column - iterate its candidate rows and verify each against all input columns through
 * the decoders. Verification is exhaustive, so the driver choice is a pure optimization; any driver
 * yields the same winner.
 *
 * <p>This replaces the bitmap-intersection pipeline, which allocated a full row-width bitmap per
 * evaluation and did O(columns × rows/64) intersection work. Here the per-evaluation work is
 * O(indexed columns × log distinct) to choose a driver plus O(driver candidates × input columns) to
 * verify, and no per-evaluation allocation scales with the table.
 *
 * <p>Two early exits: a column whose candidate count is zero proves no rule can match, and a
 * negative-operator column ({@code NE}, {@code NOT_IN}) contributes exactly that - its complement
 * match set is never worth enumerating, but its count can be zero.
 *
 * <p>The enumerated fast paths return the smallest matching physical row, which equals "first in
 * evaluation order" only when the rule order is the identity permutation - true for every artifact
 * this compiler writes, and verified at construction; otherwise every lookup takes the
 * order-faithful linear scan.
 *
 * <p>Immutable and thread-safe: holds only immutable ruleset state.
 */
final class IndexedMatcher {

  /**
   * A comparison-operator driver whose candidate slice is not row-ordered must be scanned in full
   * (tracking the minimum matching row). Past this fraction of the table, an in-order linear scan
   * with first-match early exit is the better bet.
   */
  private static final int UNORDERED_DRIVER_MAX_FRACTION = 4;

  private final List<ColumnDefinition> columns;
  private final List<ColumnDecoder> decoders;
  private final List<PostingListIndex> columnIndexes; // positional with columns; may be null
  private final int[] inputColumnIndices; // slot -> column position
  private final boolean[] testOnlySlot; // slot -> skip during matching
  private final int[] ruleOrder;
  private final int rowCount;
  private final boolean identityOrder;

  IndexedMatcher(
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      List<PostingListIndex> columnIndexes,
      int[] inputColumnIndices,
      int[] ruleOrder) {
    this.columns = columns;
    this.decoders = decoders;
    this.columnIndexes = columnIndexes;
    this.inputColumnIndices = inputColumnIndices;
    this.ruleOrder = ruleOrder;
    this.rowCount = ruleOrder.length;

    this.testOnlySlot = new boolean[inputColumnIndices.length];
    for (int k = 0; k < inputColumnIndices.length; k++) {
      testOnlySlot[k] = columns.get(inputColumnIndices[k]).isTestOnly();
    }

    boolean identity = true;
    for (int i = 0; i < ruleOrder.length; i++) {
      if (ruleOrder[i] != i) {
        identity = false;
        break;
      }
    }
    this.identityOrder = identity;
  }

  /**
   * Finds the first matching rule in evaluation order.
   *
   * @param codes per-slot coerced input codes (slot = position in the input-column order)
   * @param present per-slot flags: whether the input supplied a value for that column
   * @return the physical row of the winning rule, or -1 if no rule matches
   */
  int findFirstMatch(long[] codes, boolean[] present) {
    int driverSlot = -1;
    long driverCount = Long.MAX_VALUE;

    if (columnIndexes != null && identityOrder) {
      for (int k = 0; k < inputColumnIndices.length; k++) {
        if (testOnlySlot[k]) {
          continue;
        }
        PostingListIndex index = columnIndexes.get(inputColumnIndices[k]);
        if (index == null) {
          continue;
        }
        long count = index.candidateCount(codes[k], present[k]);
        if (count == 0) {
          return -1; // this column alone rules out every row
        }
        if (count < driverCount && index.enumerable(present[k])) {
          driverCount = count;
          driverSlot = k;
        }
      }
    }

    if (driverSlot < 0) {
      return linearScan(codes, present);
    }

    PostingListIndex driver = columnIndexes.get(inputColumnIndices[driverSlot]);
    long driverCode = codes[driverSlot];

    if (!present[driverSlot]) {
      // Absent input: only blank-cell rows are candidates; they are stored ascending.
      for (int i = 0; i < driver.blankCount(); i++) {
        int row = driver.blankRowAt(i);
        if (matchesAllInputs(codes, present, row)) {
          return row;
        }
      }
      return -1;
    }

    if (driver.matchSliceRowOrdered()) {
      // EQ/IN driver: postings slice and blanks are both ascending - merge and stop at the first
      // full match, which is the winner.
      int s = driver.matchStart(driverCode);
      int e = driver.matchEnd(driverCode);
      int b = 0;
      int bn = driver.blankCount();
      while (s < e || b < bn) {
        int row;
        if (s < e && (b >= bn || driver.postingRowAt(s) < driver.blankRowAt(b))) {
          row = driver.postingRowAt(s++);
        } else {
          row = driver.blankRowAt(b++);
        }
        if (matchesAllInputs(codes, present, row)) {
          return row;
        }
      }
      return -1;
    }

    // Comparison driver: the slice spans several codes and is not row-ordered, so the whole slice
    // must be scanned tracking the minimum matching row. Only worth it while the slice is small.
    if (driverCount > (long) rowCount / UNORDERED_DRIVER_MAX_FRACTION) {
      return linearScan(codes, present);
    }

    int min = Integer.MAX_VALUE;
    int s = driver.matchStart(driverCode);
    int e = driver.matchEnd(driverCode);
    for (int i = s; i < e; i++) {
      int row = driver.postingRowAt(i);
      if (row < min && matchesAllInputs(codes, present, row)) {
        min = row;
      }
    }
    // Blanks are ascending: the first matching blank below the current minimum is the best blank.
    for (int i = 0; i < driver.blankCount(); i++) {
      int row = driver.blankRowAt(i);
      if (row >= min) {
        break;
      }
      if (matchesAllInputs(codes, present, row)) {
        min = row;
        break;
      }
    }
    return min == Integer.MAX_VALUE ? -1 : min;
  }

  /** Order-faithful fallback: verify every rule in evaluation order, first match wins. */
  private int linearScan(long[] codes, boolean[] present) {
    for (int row : ruleOrder) {
      if (matchesAllInputs(codes, present, row)) {
        return row;
      }
    }
    return -1;
  }

  /** Exhaustive verification of one row against every non-test input column. */
  private boolean matchesAllInputs(long[] codes, boolean[] present, int rowIndex) {
    for (int k = 0; k < inputColumnIndices.length; k++) {
      if (testOnlySlot[k]) {
        continue;
      }
      if (!decoders.get(inputColumnIndices[k]).matchesCoerced(rowIndex, codes[k], present[k])) {
        return false;
      }
    }
    return true;
  }
}
