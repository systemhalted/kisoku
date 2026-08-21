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
public sealed interface ColumnIndex permits EqualityIndex, ComparisonIndex, SetMembershipIndex {
  // Note: RangeIntervalIndex will be added in a future phase

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
   * @param inputValue the coerced input code (from TypeCoercion.toComparableCode)
   * @return bitmap of candidate row indices
   */
  long[] getCandidates(long inputValue);

  /**
   * Get the candidate rows for an input that supplied no value for this column.
   *
   * <p>Only rows with blank cells qualify: a non-blank condition cannot be satisfied by an absent
   * input, so it must not survive candidate filtering. This is distinct from {@link
   * #getCandidates(long)} with a code of {@code NULL_CODE}, which represents a value that was
   * supplied but is unknown to the dictionary.
   *
   * @return bitmap of rows whose cell in this column is blank
   */
  long[] candidatesForAbsentInput();

  /**
   * Estimate memory usage of this index in bytes.
   *
   * @return estimated memory size
   */
  long memorySizeBytes();
}
