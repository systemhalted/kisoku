package in.systemhalted.kisoku.runtime.loader.index;

/**
 * Per-column candidate index: exact selectivity counts and, where the operator allows it,
 * enumeration of candidate rows.
 *
 * <p>The evaluator asks every indexed column for its {@link #candidateCount} (a count of zero
 * proves no rule can match), picks the enumerable column with the lowest {@link #enumerationCost}
 * as its driver, and verifies the driver's candidates exhaustively - so counts must be exact and
 * enumerations must be supersets of the true match set, but enumerations may over-approximate
 * (verification filters).
 *
 * <p>Candidates are exposed as a slice of a virtual row array: {@code [matchStart(code),
 * matchEnd(code))} indexed through {@link #rowAt(int)}, plus the blank-cell rows (always
 * candidates; an absent input's only candidates). Implementations are immutable and thread-safe.
 */
public sealed interface ColumnIndex permits PostingListIndex, RangeIntervalIndex {

  /**
   * Exact number of rows this column allows for the input, blanks included.
   *
   * @param code the input's comparable code (meaningful only when {@code present})
   * @param present whether the input supplied a value for this column
   * @return the candidate count; 0 means no rule can match and evaluation can stop
   */
  long candidateCount(long code, boolean present);

  /**
   * Whether candidates can be enumerated for this input. Always true for an absent input (blanks
   * only); false for complement-matching operators ({@code NE}, {@code NOT_IN}, {@code
   * NOT_BETWEEN_*}).
   *
   * @param present whether the input supplied a value
   * @return true if this column can drive matching
   */
  boolean enumerable(boolean present);

  /**
   * Rows a driver enumeration would visit for this input, blanks included. Equal to {@link
   * #candidateCount} when the enumeration is exact; larger when it is a superset (interval indexes
   * enumerate one bound's side).
   *
   * @param code the input's comparable code
   * @param present whether the input supplied a value
   * @return the enumeration cost, for driver selection
   */
  long enumerationCost(long code, boolean present);

  /**
   * Whether {@code rowAt} over the match slice yields ascending row ids. When true, the first
   * fully-verified candidate is the winner; otherwise the evaluator tracks the minimum.
   *
   * @return true if the match slice is row-ordered
   */
  boolean enumerationRowOrdered();

  /**
   * Start of the matching slice for a supplied value (inclusive), in this index's virtual row
   * space.
   *
   * @param code the input's comparable code
   * @return slice start
   */
  int matchStart(long code);

  /**
   * End of the matching slice for a supplied value (exclusive).
   *
   * @param code the input's comparable code
   * @return slice end
   */
  int matchEnd(long code);

  /**
   * Reads a candidate row id from the virtual row space.
   *
   * @param index a position in [matchStart, matchEnd)
   * @return the row id
   */
  int rowAt(int index);

  /**
   * Number of blank-cell rows (always candidates).
   *
   * @return the blank row count
   */
  int blankCount();

  /**
   * Reads a blank row id; blank rows are stored ascending.
   *
   * @param index blank index in [0, blankCount)
   * @return the row id
   */
  int blankRowAt(int index);

  /**
   * Memory held by this index.
   *
   * @return the backing buffer's capacity in bytes (off-heap for direct buffers)
   */
  long memorySizeBytes();
}
