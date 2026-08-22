package in.systemhalted.kisoku.runtime.loader.index;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Off-heap interval index for range-operator columns ({@code BETWEEN_INCLUSIVE}, {@code
 * BETWEEN_EXCLUSIVE}, {@code NOT_BETWEEN_INCLUSIVE}, {@code NOT_BETWEEN_EXCLUSIVE}).
 *
 * <p>Each included condition row appears in two CSR posting structures - one sorted by the row's
 * <em>min</em> code, one by its <em>max</em> code - in a single direct buffer:
 *
 * <pre>
 * minCodes[distinctMin]      (8 bytes each)  - sorted ascending
 * minOffsets[distinctMin+1]  (4 bytes each)
 * minPostings[includedRows]  (4 bytes each)  - rows ascending within a code
 * maxCodes[distinctMax]      (8 bytes each)
 * maxOffsets[distinctMax+1]  (4 bytes each)
 * maxPostings[includedRows]  (4 bytes each)
 * blanks[blankCount]         (4 bytes each)  - ascending
 * </pre>
 *
 * <p><b>Exact counting by inclusion-exclusion.</b> For an input {@code v}, the rows matching {@code
 * BETWEEN_INCLUSIVE} are exactly {@code #(min &le; v) - #(max &lt; v)}: every included row has
 * {@code min &le; max}, so a row with {@code min &gt; v} also has {@code max &gt; v} and
 * contributes to neither term. The exclusive variant uses strict/na-strict bounds. Rows whose
 * bounds are inverted for their operator ({@code min &gt; max} inclusive, {@code min &ge; max}
 * exclusive) can never match and are <em>excluded from both structures</em> - which keeps the
 * subtraction sound - while still counted in {@code conditionRowCount}, so the {@code
 * NOT_BETWEEN_*} complement correctly counts them as always matching.
 *
 * <p><b>Enumeration by the cheaper bound.</b> A {@code BETWEEN} match set is not contiguous in
 * either structure, but each bound gives a contiguous <em>superset</em>: rows with {@code min &le;
 * v} (a prefix of the min-sorted postings) or rows with {@code max &ge; v} (a suffix of the
 * max-sorted postings). The index exposes whichever side is smaller through a virtual row space -
 * indexes below {@code includedRows} read the min postings, the rest read the max postings - and
 * the evaluator's exhaustive verification filters the superset. Negative operators match
 * complements and are count-only, like {@code NE}/{@code NOT_IN}.
 *
 * <p>Immutable and thread-safe: all reads go through absolute buffer gets.
 */
public final class RangeIntervalIndex implements ColumnIndex {

  private final ByteBuffer buffer;
  private final Operator operator;
  private final int distinctMin;
  private final int distinctMax;
  private final int includedRows; // condition rows that can ever match (non-inverted bounds)
  private final int blankCount;
  private final int conditionRowCount; // all condition rows (for complement counts)

  private final int minCodesBase;
  private final int minOffsetsBase;
  private final int minPostingsBase;
  private final int maxCodesBase;
  private final int maxOffsetsBase;
  private final int maxPostingsBase;
  private final int blanksBase;

  RangeIntervalIndex(
      ByteBuffer buffer,
      Operator operator,
      int distinctMin,
      int distinctMax,
      int includedRows,
      int blankCount,
      int conditionRowCount) {
    this.buffer = buffer;
    this.operator = operator;
    this.distinctMin = distinctMin;
    this.distinctMax = distinctMax;
    this.includedRows = includedRows;
    this.blankCount = blankCount;
    this.conditionRowCount = conditionRowCount;

    this.minCodesBase = 0;
    this.minOffsetsBase = minCodesBase + distinctMin * 8;
    this.minPostingsBase = minOffsetsBase + (distinctMin + 1) * 4;
    this.maxCodesBase = minPostingsBase + includedRows * 4;
    this.maxOffsetsBase = maxCodesBase + distinctMax * 8;
    this.maxPostingsBase = maxOffsetsBase + (distinctMax + 1) * 4;
    this.blanksBase = maxPostingsBase + includedRows * 4;
  }

  /**
   * Builds an interval index from decoded range-column data.
   *
   * @param mins per-row min codes
   * @param maxs per-row max codes
   * @param presenceBitmap the presence bitmap (MSB-first)
   * @param operator the range operator
   * @param rowCount total number of rows
   * @return the built index
   */
  public static RangeIntervalIndex build(
      long[] mins, long[] maxs, byte[] presenceBitmap, Operator operator, int rowCount) {
    boolean exclusiveBounds =
        operator == Operator.BETWEEN_EXCLUSIVE || operator == Operator.NOT_BETWEEN_EXCLUSIVE;

    int conditionRows = 0;
    int included = 0;
    int blanks = 0;
    for (int row = 0; row < rowCount; row++) {
      if (!isPresent(presenceBitmap, row)) {
        blanks++;
        continue;
      }
      conditionRows++;
      if (canEverMatch(mins[row], maxs[row], exclusiveBounds)) {
        included++;
      }
    }

    long[] minCodes = new long[included];
    int[] minRows = new int[included];
    long[] maxCodes = new long[included];
    int[] maxRows = new int[included];
    int[] blankRows = new int[blanks];
    int p = 0;
    int b = 0;
    for (int row = 0; row < rowCount; row++) {
      if (!isPresent(presenceBitmap, row)) {
        blankRows[b++] = row;
        continue;
      }
      if (!canEverMatch(mins[row], maxs[row], exclusiveBounds)) {
        continue; // inverted bounds: never a BETWEEN match, always a NOT_BETWEEN match
      }
      minCodes[p] = mins[row];
      minRows[p] = row;
      maxCodes[p] = maxs[row];
      maxRows[p] = row;
      p++;
    }

    PostingListIndexBuilder.stableSortByCode(minCodes, minRows, included);
    PostingListIndexBuilder.stableSortByCode(maxCodes, maxRows, included);

    int dMin = distinctCount(minCodes, included);
    int dMax = distinctCount(maxCodes, included);

    long byteSize =
        8L * dMin
            + 4L * (dMin + 1)
            + 4L * included
            + 8L * dMax
            + 4L * (dMax + 1)
            + 4L * included
            + 4L * blanks;
    if (byteSize > Integer.MAX_VALUE) {
      throw new IllegalStateException("Index exceeds 2GB buffer limit: " + byteSize + " bytes");
    }
    ByteBuffer buffer = ByteBuffer.allocateDirect((int) byteSize).order(ByteOrder.BIG_ENDIAN);
    writeCsr(buffer, minCodes, minRows, included);
    writeCsr(buffer, maxCodes, maxRows, included);
    for (int i = 0; i < blanks; i++) {
      buffer.putInt(blankRows[i]);
    }

    return new RangeIntervalIndex(buffer, operator, dMin, dMax, included, blanks, conditionRows);
  }

  private static boolean canEverMatch(long min, long max, boolean exclusiveBounds) {
    return exclusiveBounds ? min < max : min <= max;
  }

  private static int distinctCount(long[] codes, int count) {
    int distinct = 0;
    for (int i = 0; i < count; i++) {
      if (i == 0 || codes[i] != codes[i - 1]) {
        distinct++;
      }
    }
    return distinct;
  }

  private static void writeCsr(ByteBuffer buffer, long[] codes, int[] rows, int count) {
    for (int i = 0; i < count; i++) {
      if (i == 0 || codes[i] != codes[i - 1]) {
        buffer.putLong(codes[i]);
      }
    }
    buffer.putInt(0);
    for (int i = 1; i <= count; i++) {
      if (i == count || codes[i] != codes[i - 1]) {
        buffer.putInt(i);
      }
    }
    for (int i = 0; i < count; i++) {
      buffer.putInt(rows[i]);
    }
  }

  private static boolean isPresent(byte[] bitmap, int rowIndex) {
    return (bitmap[rowIndex / 8] & (1 << (7 - (rowIndex % 8)))) != 0;
  }

  /** Serialized block size in bytes: the 20-byte header plus the array region. */
  public long serializedSize() {
    return 20L + buffer.capacity();
  }

  /**
   * Serializes this index as an artifact block: {@code distinctMin, distinctMax, includedRows,
   * blankCount, conditionRowCount} (4 bytes each) followed by the array region byte-for-byte.
   *
   * @param out the artifact stream
   * @throws java.io.IOException if the stream fails
   */
  public void writeTo(java.io.DataOutputStream out) throws java.io.IOException {
    out.writeInt(distinctMin);
    out.writeInt(distinctMax);
    out.writeInt(includedRows);
    out.writeInt(blankCount);
    out.writeInt(conditionRowCount);
    PostingListIndex.writeBuffer(buffer, out);
  }

  /**
   * Constructs an index over a persisted block without copying.
   *
   * @param block the block's bytes, starting at its 20-byte header
   * @param operator the column's range operator
   * @return the index
   */
  public static RangeIntervalIndex readFrom(ByteBuffer block, Operator operator) {
    int distinctMin = block.getInt(0);
    int distinctMax = block.getInt(4);
    int includedRows = block.getInt(8);
    int blankCount = block.getInt(12);
    int conditionRowCount = block.getInt(16);
    ByteBuffer arrays = block.slice(20, block.capacity() - 20).order(java.nio.ByteOrder.BIG_ENDIAN);
    return new RangeIntervalIndex(
        arrays, operator, distinctMin, distinctMax, includedRows, blankCount, conditionRowCount);
  }

  // ------------------------------------------------------------------ reads

  @Override
  public long candidateCount(long code, boolean present) {
    if (!present) {
      return blankCount;
    }
    long between = betweenCount(code);
    return switch (operator) {
      case BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE -> between + blankCount;
      case NOT_BETWEEN_INCLUSIVE, NOT_BETWEEN_EXCLUSIVE ->
          (conditionRowCount - between) + blankCount;
      default -> throw new IllegalStateException("Not a range operator: " + operator);
    };
  }

  /** Exact number of included rows whose interval contains the input, by inclusion-exclusion. */
  private long betweenCount(long code) {
    return switch (operator) {
      case BETWEEN_INCLUSIVE, NOT_BETWEEN_INCLUSIVE ->
          minsAtOrBelow(code) - maxesStrictlyBelow(code);
      case BETWEEN_EXCLUSIVE, NOT_BETWEEN_EXCLUSIVE ->
          minsStrictlyBelow(code) - maxesAtOrBelow(code);
      default -> throw new IllegalStateException("Not a range operator: " + operator);
    };
  }

  @Override
  public boolean enumerable(boolean present) {
    if (!present) {
      return true;
    }
    return operator == Operator.BETWEEN_INCLUSIVE || operator == Operator.BETWEEN_EXCLUSIVE;
  }

  @Override
  public long enumerationCost(long code, boolean present) {
    if (!present) {
      return blankCount;
    }
    return Math.min(minSideCount(code), maxSideCount(code)) + blankCount;
  }

  @Override
  public boolean enumerationRowOrdered() {
    return false;
  }

  @Override
  public int matchStart(long code) {
    if (minSideCount(code) <= maxSideCount(code)) {
      return 0;
    }
    return includedRows + (includedRows - (int) maxSideCount(code));
  }

  @Override
  public int matchEnd(long code) {
    long minSide = minSideCount(code);
    if (minSide <= maxSideCount(code)) {
      return (int) minSide;
    }
    return includedRows + includedRows;
  }

  @Override
  public int rowAt(int index) {
    if (index < includedRows) {
      return buffer.getInt(minPostingsBase + index * 4);
    }
    return buffer.getInt(maxPostingsBase + (index - includedRows) * 4);
  }

  @Override
  public int blankCount() {
    return blankCount;
  }

  @Override
  public int blankRowAt(int index) {
    return buffer.getInt(blanksBase + index * 4);
  }

  @Override
  public long memorySizeBytes() {
    return buffer.capacity();
  }

  // Superset side sizes: every match satisfies its column's min-bound and max-bound predicates.

  /** Rows whose min bound admits the input (a prefix of the min-sorted postings). */
  private long minSideCount(long code) {
    return operator == Operator.BETWEEN_INCLUSIVE ? minsAtOrBelow(code) : minsStrictlyBelow(code);
  }

  /** Rows whose max bound admits the input (a suffix of the max-sorted postings). */
  private long maxSideCount(long code) {
    return operator == Operator.BETWEEN_INCLUSIVE
        ? includedRows - maxesStrictlyBelow(code)
        : includedRows - maxesAtOrBelow(code);
  }

  private long minsAtOrBelow(long code) {
    return offsetAt(minOffsetsBase, upperBound(minCodesBase, distinctMin, code));
  }

  private long minsStrictlyBelow(long code) {
    return offsetAt(minOffsetsBase, lowerBound(minCodesBase, distinctMin, code));
  }

  private long maxesStrictlyBelow(long code) {
    return offsetAt(maxOffsetsBase, lowerBound(maxCodesBase, distinctMax, code));
  }

  private long maxesAtOrBelow(long code) {
    return offsetAt(maxOffsetsBase, upperBound(maxCodesBase, distinctMax, code));
  }

  private int offsetAt(int offsetsBase, int idx) {
    return buffer.getInt(offsetsBase + idx * 4);
  }

  private long codeAt(int codesBase, int idx) {
    return buffer.getLong(codesBase + idx * 8);
  }

  /** First index with codes[idx] >= code, or {@code distinct} if none. */
  private int lowerBound(int codesBase, int distinct, long code) {
    int lo = 0;
    int hi = distinct;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (codeAt(codesBase, mid) < code) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** First index with codes[idx] > code, or {@code distinct} if none. */
  private int upperBound(int codesBase, int distinct, long code) {
    int lo = 0;
    int hi = distinct;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (codeAt(codesBase, mid) <= code) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
