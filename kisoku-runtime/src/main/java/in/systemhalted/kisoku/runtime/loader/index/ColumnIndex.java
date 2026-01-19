package in.systemhalted.kisoku.runtime.loader.index;

/**
 * Index for a single column to enable fast candidate filtering.
 *
 * <p>Each index type supports different operators:
 *
 * <ul>
 *   <li>{@link EqualityIndex} - EQ operator (hash-based lookup)
 *   <li>ComparisonIndex - GT, GTE, LT, LTE operators (sorted array + binary search) [Phase 2]
 *   <li>RangeIntervalIndex - BETWEEN_* operators (interval tree) [Phase 2]
 *   <li>SetMembershipIndex - IN, NOT_IN operators (inverted index) [Phase 2]
 * </ul>
 *
 * <p>Indexes are immutable and thread-safe after construction.
 */
public sealed interface ColumnIndex permits EqualityIndex {
  // Note: ComparisonIndex, RangeIntervalIndex, SetMembershipIndex will be added in Phase 2

  /**
   * Get the candidate rows that could match the given input value.
   *
   * <p>The returned bitmap includes:
   *
   * <ul>
   *   <li>Rows where the column value matches the input (per operator semantics)
   *   <li>Rows with blank cells (no condition - always match)
   * </ul>
   *
   * @param inputValue the coerced input value (from TypeCoercion.toComparableInt)
   * @return bitmap of candidate row indices
   */
  long[] getCandidates(int inputValue);

  /**
   * Estimate memory usage of this index in bytes.
   *
   * @return estimated memory size
   */
  long memorySizeBytes();
}
