package in.systemhalted.kisoku.runtime.loader;

/**
 * A columnar batch of inputs for the bulk evaluation kernel.
 *
 * <p>Inputs are stored column-major as pre-coerced comparable ints (the same domain produced by
 * {@link TypeCoercion#toComparableInt}), so the kernel never re-coerces per cell. {@code codes} is
 * indexed {@code [inputSlot][row]}, where {@code inputSlot} aligns with the ruleset's input-column
 * order (see {@code ColumnarBulkKernel}).
 *
 * <p>{@code present} is tracked separately from the code because the coerced domain cannot express
 * absence: a field the caller omitted and a supplied string that is unknown to the dictionary both
 * coerce to {@code NULL_ID}, but only the latter may satisfy a condition.
 */
final class InputBatch {
  private final int[][] codes; // [inputSlot][row]
  private final boolean[][] present; // [inputSlot][row]
  private final int rowCount;

  InputBatch(int[][] codes, boolean[][] present, int rowCount) {
    this.codes = codes;
    this.present = present;
    this.rowCount = rowCount;
  }

  int rowCount() {
    return rowCount;
  }

  /** Coerced code for the given input slot and row. */
  int code(int inputSlot, int row) {
    return codes[inputSlot][row];
  }

  /** Whether the input at the given slot and row supplied a value at all. */
  boolean present(int inputSlot, int row) {
    return present[inputSlot][row];
  }
}
