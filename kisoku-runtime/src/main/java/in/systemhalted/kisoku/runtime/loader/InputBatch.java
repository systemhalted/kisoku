package in.systemhalted.kisoku.runtime.loader;

/**
 * A columnar batch of inputs for the bulk evaluation kernel.
 *
 * <p>Inputs are stored column-major as pre-coerced comparable ints (the same domain produced by
 * {@link TypeCoercion#toComparableInt}), so the kernel never re-coerces per cell. {@code codes} is
 * indexed {@code [inputSlot][row]}, where {@code inputSlot} aligns with the ruleset's input-column
 * order (see {@code ColumnarBulkKernel}). Missing values are encoded as {@code NULL_ID} (0),
 * matching single-eval semantics.
 */
final class InputBatch {
  private final int[][] codes; // [inputSlot][row]
  private final int rowCount;

  InputBatch(int[][] codes, int rowCount) {
    this.codes = codes;
    this.rowCount = rowCount;
  }

  int rowCount() {
    return rowCount;
  }

  /** Coerced code for the given input slot and row. */
  int code(int inputSlot, int row) {
    return codes[inputSlot][row];
  }
}
