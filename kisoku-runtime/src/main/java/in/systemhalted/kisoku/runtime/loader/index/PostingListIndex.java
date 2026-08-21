package in.systemhalted.kisoku.runtime.loader.index;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Off-heap posting-list index for one input column.
 *
 * <p>Replaces the dense per-value bitmaps (one {@code long[ceil(rows/64)]} per distinct value, on
 * heap) whose cost was {@code distinct × rows / 8} bytes regardless of how sparse each value was.
 * This structure stores each present cell exactly once, in a single direct buffer:
 *
 * <pre>
 * codes[distinct]      (8 bytes each)  - distinct comparable codes, sorted ascending
 * offsets[distinct+1]  (4 bytes each)  - postings[offsets[i] .. offsets[i+1]) hold code i's rows
 * postings[present]    (4 bytes each)  - row ids, ascending within each code
 * blanks[blankCount]   (4 bytes each)  - rows with a blank cell, ascending
 * </pre>
 *
 * <p>Size is {@code 8·distinct + 4·(distinct+1) + 4·present + 4·blanks} bytes — linear in the
 * column's data, off the Java heap, independent of value skew. For a 5M-row column that is ~20 MB
 * versus ~312 MB for the dense form at 500 distinct values.
 *
 * <p>The index answers two questions:
 *
 * <ul>
 *   <li>{@link #candidateCount(long, boolean)} — exactly how many rows could match this input, from
 *       offset arithmetic in O(log distinct). This is an exact selectivity, which the evaluator
 *       uses to pick the most selective column as its driver.
 *   <li>{@link #matchStart(long)}/{@link #matchEnd(long)} — for operators that can enumerate, the
 *       contiguous postings slice of matching rows. Equality-shaped operators ({@code EQ}, {@code
 *       IN}) yield a single code's slice, so rows come back in ascending (= evaluation) order;
 *       comparison operators yield a multi-code slice that is not row-ordered, which the evaluator
 *       handles by tracking the minimum matching row.
 * </ul>
 *
 * <p>Negative operators ({@code NE}, {@code NOT_IN}) match the complement of a code's postings. The
 * complement is nearly the whole table, so they are never worth enumerating; they still provide
 * exact counts (condition rows minus the code's postings), which gives the evaluator a correct
 * early exit when a negative column can match nothing at all.
 *
 * <p>Blank cells carry no condition and match any input, so blank rows are candidates for every
 * lookup; an absent input (the field was not supplied) matches <em>only</em> blank rows.
 *
 * <p>Immutable and thread-safe: all reads go through absolute buffer gets.
 */
public final class PostingListIndex {

  private final ByteBuffer buffer; // direct; absolute reads only
  private final Operator operator;
  private final int distinct;
  private final int codesBase; // byte offset of codes[]
  private final int offsetsBase; // byte offset of offsets[]
  private final int postingsBase; // byte offset of postings[]
  private final int postingCount;
  private final int blanksBase; // byte offset of blanks[]
  private final int blankCount;
  private final int conditionRowCount; // rows with a non-blank cell (for complement counts)

  PostingListIndex(
      ByteBuffer buffer,
      Operator operator,
      int distinct,
      int postingCount,
      int blankCount,
      int conditionRowCount) {
    this.buffer = buffer;
    this.operator = operator;
    this.distinct = distinct;
    this.codesBase = 0;
    this.offsetsBase = codesBase + distinct * 8;
    this.postingsBase = offsetsBase + (distinct + 1) * 4;
    this.postingCount = postingCount;
    this.blanksBase = postingsBase + postingCount * 4;
    this.blankCount = blankCount;
    this.conditionRowCount = conditionRowCount;
  }

  /**
   * Exact number of candidate rows for this input, blanks included.
   *
   * @param code the input's comparable code (meaningful only when {@code present})
   * @param present whether the input supplied a value for this column
   * @return the candidate count; 0 means no rule can match and evaluation can stop
   */
  public long candidateCount(long code, boolean present) {
    if (!present) {
      return blankCount;
    }
    return matchCount(code) + blankCount;
  }

  /** Number of condition rows matching the code under this column's operator (blanks excluded). */
  private long matchCount(long code) {
    return switch (operator) {
      case EQ, IN -> {
        int idx = codeSearch(code);
        yield idx < 0 ? 0 : offsetAt(idx + 1) - offsetAt(idx);
      }
      case NE, NOT_IN -> {
        int idx = codeSearch(code);
        long matched = idx < 0 ? 0 : offsetAt(idx + 1) - offsetAt(idx);
        yield conditionRowCount - matched;
      }
      case GT, GTE, LT, LTE -> {
        int[] range = comparisonCodeRange(code);
        yield offsetAt(range[1]) - offsetAt(range[0]);
      }
      default -> throw new IllegalStateException("Unsupported operator: " + operator);
    };
  }

  /**
   * Whether candidates can be enumerated as a postings slice for this input.
   *
   * <p>Always true for an absent input (blanks only). For a supplied value, true for the positive
   * operators; false for {@code NE}/{@code NOT_IN}, whose match set is a complement.
   *
   * @param present whether the input supplied a value
   * @return true if {@link #matchStart(long)}/{@link #matchEnd(long)} (or the blank slice alone)
   *     cover the candidates
   */
  public boolean enumerable(boolean present) {
    if (!present) {
      return true;
    }
    return switch (operator) {
      case EQ, IN, GT, GTE, LT, LTE -> true;
      default -> false;
    };
  }

  /**
   * Whether the postings slice for a supplied value is in ascending row order.
   *
   * <p>True for single-code slices ({@code EQ}, {@code IN}); false for comparison operators, whose
   * slice spans several codes.
   *
   * @return true if the match slice is row-ordered
   */
  public boolean matchSliceRowOrdered() {
    return operator == Operator.EQ || operator == Operator.IN;
  }

  /**
   * Start of the matching postings slice for a supplied value (inclusive).
   *
   * @param code the input's comparable code
   * @return slice start index into the postings array
   */
  public int matchStart(long code) {
    return switch (operator) {
      case EQ, IN -> {
        int idx = codeSearch(code);
        yield idx < 0 ? 0 : offsetAt(idx);
      }
      case GT, GTE, LT, LTE -> offsetAt(comparisonCodeRange(code)[0]);
      default -> throw new IllegalStateException("Not enumerable: " + operator);
    };
  }

  /**
   * End of the matching postings slice for a supplied value (exclusive).
   *
   * @param code the input's comparable code
   * @return slice end index into the postings array
   */
  public int matchEnd(long code) {
    return switch (operator) {
      case EQ, IN -> {
        int idx = codeSearch(code);
        yield idx < 0 ? 0 : offsetAt(idx + 1);
      }
      case GT, GTE, LT, LTE -> offsetAt(comparisonCodeRange(code)[1]);
      default -> throw new IllegalStateException("Not enumerable: " + operator);
    };
  }

  /**
   * Reads a row id from the postings array.
   *
   * @param index postings index in [matchStart, matchEnd)
   * @return the row id
   */
  public int postingRowAt(int index) {
    return buffer.getInt(postingsBase + index * 4);
  }

  /**
   * Number of blank-cell rows (always candidates).
   *
   * @return the blank row count
   */
  public int blankCount() {
    return blankCount;
  }

  /**
   * Reads a blank row id; blank rows are stored ascending.
   *
   * @param index blank index in [0, blankCount)
   * @return the row id
   */
  public int blankRowAt(int index) {
    return buffer.getInt(blanksBase + index * 4);
  }

  /**
   * Number of distinct codes indexed.
   *
   * @return the distinct code count
   */
  public int distinctCodeCount() {
    return distinct;
  }

  /**
   * Memory held by this index.
   *
   * @return the backing buffer's capacity in bytes (off-heap for direct buffers)
   */
  public long memorySizeBytes() {
    return buffer.capacity();
  }

  /**
   * The code range [fromIdx, toIdxExclusive) whose rows satisfy this comparison operator for the
   * input. A rule row with threshold T matches input V when: GT → V &gt; T, GTE → V ≥ T, LT → V
   * &lt; T, LTE → V ≤ T. So GT needs thresholds below V, LT thresholds above V, and so on.
   */
  private int[] comparisonCodeRange(long code) {
    int lb = lowerBound(code); // first idx with codes[idx] >= code
    int ub = upperBound(code); // first idx with codes[idx] > code
    return switch (operator) {
      case GT -> new int[] {0, lb};
      case GTE -> new int[] {0, ub};
      case LT -> new int[] {ub, distinct};
      case LTE -> new int[] {lb, distinct};
      default -> throw new IllegalStateException("Not a comparison operator: " + operator);
    };
  }

  private long codeAt(int idx) {
    return buffer.getLong(codesBase + idx * 8);
  }

  private int offsetAt(int idx) {
    return buffer.getInt(offsetsBase + idx * 4);
  }

  /** Binary search over codes; returns the index, or a negative value if absent. */
  private int codeSearch(long code) {
    int lb = lowerBound(code);
    if (lb < distinct && codeAt(lb) == code) {
      return lb;
    }
    return -1;
  }

  /** First index with codes[idx] >= code, or {@code distinct} if none. */
  private int lowerBound(long code) {
    int lo = 0;
    int hi = distinct;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (codeAt(mid) < code) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** First index with codes[idx] > code, or {@code distinct} if none. */
  private int upperBound(long code) {
    int lo = 0;
    int hi = distinct;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (codeAt(mid) <= code) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
