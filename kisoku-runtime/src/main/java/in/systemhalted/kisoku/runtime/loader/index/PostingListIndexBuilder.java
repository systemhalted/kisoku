package in.systemhalted.kisoku.runtime.loader.index;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Builds a {@link PostingListIndex} from (code, row) pairs into a direct buffer.
 *
 * <p>Construction sorts the pairs by code with a stable merge sort, so rows stay ascending within
 * each code (pairs must be supplied in ascending row order). The transient sort arrays are the only
 * heap cost, one column at a time; the finished index lives entirely off-heap.
 */
public final class PostingListIndexBuilder {

  private final Operator operator;
  private final int conditionRowCount;
  private long[] codes;
  private int[] rows;
  private int pairCount;
  private int[] blanks;
  private int blankCount;

  /**
   * Creates a builder.
   *
   * @param operator the column's operator
   * @param conditionRowCount number of rows with a non-blank cell (for complement counts)
   * @param expectedPairs expected number of (code, row) pairs, as a capacity hint
   */
  public PostingListIndexBuilder(Operator operator, int conditionRowCount, int expectedPairs) {
    this.operator = operator;
    this.conditionRowCount = conditionRowCount;
    this.codes = new long[Math.max(16, expectedPairs)];
    this.rows = new int[Math.max(16, expectedPairs)];
    this.blanks = new int[16];
  }

  /**
   * Adds one (code, row) pair. Calls must arrive in ascending row order; a set-membership row adds
   * one pair per member.
   *
   * @param code the cell's comparable code
   * @param row the row id
   */
  public void addPair(long code, int row) {
    if (pairCount == codes.length) {
      int grown = codes.length + (codes.length >> 1);
      long[] newCodes = new long[grown];
      int[] newRows = new int[grown];
      System.arraycopy(codes, 0, newCodes, 0, pairCount);
      System.arraycopy(rows, 0, newRows, 0, pairCount);
      codes = newCodes;
      rows = newRows;
    }
    codes[pairCount] = code;
    rows[pairCount] = row;
    pairCount++;
  }

  /**
   * Records a blank-cell row. Calls must arrive in ascending row order.
   *
   * @param row the row id
   */
  public void addBlank(int row) {
    if (blankCount == blanks.length) {
      int[] grown = new int[blanks.length * 2];
      System.arraycopy(blanks, 0, grown, 0, blankCount);
      blanks = grown;
    }
    blanks[blankCount++] = row;
  }

  /**
   * Sorts, deduplicates, and writes the index into a freshly allocated direct buffer.
   *
   * @return the finished index
   */
  public PostingListIndex build() {
    stableSortByCode();

    // Deduplicate identical (code, row) pairs - a set cell listing the same member twice would
    // otherwise double-count that row in complement arithmetic.
    int unique = 0;
    for (int i = 0; i < pairCount; i++) {
      if (i > 0 && codes[i] == codes[unique - 1] && rows[i] == rows[unique - 1]) {
        continue;
      }
      codes[unique] = codes[i];
      rows[unique] = rows[i];
      unique++;
    }

    int distinct = 0;
    for (int i = 0; i < unique; i++) {
      if (i == 0 || codes[i] != codes[i - 1]) {
        distinct++;
      }
    }

    long byteSize = 8L * distinct + 4L * (distinct + 1) + 4L * unique + 4L * blankCount;
    if (byteSize > Integer.MAX_VALUE) {
      throw new IllegalStateException("Index exceeds 2GB buffer limit: " + byteSize + " bytes");
    }
    ByteBuffer buffer = ByteBuffer.allocateDirect((int) byteSize).order(ByteOrder.BIG_ENDIAN);

    // codes[]
    for (int i = 0; i < unique; i++) {
      if (i == 0 || codes[i] != codes[i - 1]) {
        buffer.putLong(codes[i]);
      }
    }
    // offsets[]
    buffer.putInt(0);
    for (int i = 1; i <= unique; i++) {
      if (i == unique || codes[i] != codes[i - 1]) {
        buffer.putInt(i);
      }
    }
    // postings[]
    for (int i = 0; i < unique; i++) {
      buffer.putInt(rows[i]);
    }
    // blanks[]
    for (int i = 0; i < blankCount; i++) {
      buffer.putInt(blanks[i]);
    }

    // Release the transient arrays before handing back the index.
    codes = null;
    rows = null;
    blanks = null;

    return new PostingListIndex(buffer, operator, distinct, unique, blankCount, conditionRowCount);
  }

  /**
   * Bottom-up stable merge sort of (codes, rows) by code. Stability preserves the ascending row
   * order within each code that the insertion order guarantees.
   */
  private void stableSortByCode() {
    if (pairCount < 2) {
      return;
    }
    long[] codeTmp = new long[pairCount];
    int[] rowTmp = new int[pairCount];
    for (int width = 1; width < pairCount; width *= 2) {
      for (int lo = 0; lo < pairCount - width; lo += 2 * width) {
        int mid = lo + width;
        int hi = Math.min(lo + 2 * width, pairCount);
        if (codes[mid - 1] <= codes[mid]) {
          continue; // already ordered across the boundary
        }
        int i = lo;
        int j = mid;
        int k = lo;
        while (i < mid && j < hi) {
          if (codes[i] <= codes[j]) {
            codeTmp[k] = codes[i];
            rowTmp[k] = rows[i];
            i++;
          } else {
            codeTmp[k] = codes[j];
            rowTmp[k] = rows[j];
            j++;
          }
          k++;
        }
        while (i < mid) {
          codeTmp[k] = codes[i];
          rowTmp[k] = rows[i];
          i++;
          k++;
        }
        while (j < hi) {
          codeTmp[k] = codes[j];
          rowTmp[k] = rows[j];
          j++;
          k++;
        }
        System.arraycopy(codeTmp, lo, codes, lo, hi - lo);
        System.arraycopy(rowTmp, lo, rows, lo, hi - lo);
      }
    }
  }
}
