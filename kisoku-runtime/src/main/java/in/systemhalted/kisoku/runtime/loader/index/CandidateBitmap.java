package in.systemhalted.kisoku.runtime.loader.index;

/**
 * Utility for bitmap operations on candidate row sets.
 *
 * <p>Uses {@code long[]} for efficient 64-bit bitwise operations. For 5M rows, each bitmap is ~625
 * KB (78,125 longs).
 *
 * <p>*Bit indexing*: bit *i* is at {@code words[i / 64]} bit {@code (i % 64)}, stored LSB-first
 * within each word.
 */
public final class CandidateBitmap {
  private CandidateBitmap() {}

  /**
   * Calculate number of longs needed for the given row count.
   *
   * @param rowCount number of rows
   * @return number of longs needed
   */
  public static int longCount(int rowCount) {
    return (rowCount + 63) / 64;
  }

  /**
   * Create a bitmap with all bits set (all rows are candidates).
   *
   * @param rowCount number of rows
   * @return bitmap with all bits set up to rowCount
   */
  public static long[] allOnes(int rowCount) {
    int longCount = longCount(rowCount);
    long[] bitmap = new long[longCount];

    // Fill all complete words with all 1s
    for (int i = 0; i < longCount - 1; i++) {
      bitmap[i] = ~0L;
    }

    // Handle the last word - only set bits up to rowCount
    int remainingBits = rowCount % 64;
    if (remainingBits == 0 && rowCount > 0) {
      bitmap[longCount - 1] = ~0L;
    } else if (remainingBits > 0) {
      bitmap[longCount - 1] = (1L << remainingBits) - 1;
    }

    return bitmap;
  }

  /**
   * Create an empty bitmap (no candidates).
   *
   * @param rowCount number of rows
   * @return bitmap with all bits clear
   */
  public static long[] empty(int rowCount) {
    return new long[longCount(rowCount)];
  }

  /**
   * Bitwise AND of two bitmaps. Result stored in a new array.
   *
   * @param a first bitmap
   * @param b second bitmap
   * @return new bitmap containing a AND b
   */
  public static long[] and(long[] a, long[] b) {
    int len = Math.min(a.length, b.length);
    long[] result = new long[len];
    for (int i = 0; i < len; i++) {
      result[i] = a[i] & b[i];
    }
    return result;
  }

  /**
   * Bitwise AND of two bitmaps, storing result in the first array (mutating).
   *
   * @param a first bitmap (modified in place)
   * @param b second bitmap
   */
  public static void andInPlace(long[] a, long[] b) {
    int len = Math.min(a.length, b.length);
    for (int i = 0; i < len; i++) {
      a[i] &= b[i];
    }
    // Clear any remaining words in a if b is shorter
    for (int i = len; i < a.length; i++) {
      a[i] = 0L;
    }
  }

  /**
   * Bitwise OR of two bitmaps. Result stored in a new array.
   *
   * @param a first bitmap
   * @param b second bitmap
   * @return new bitmap containing a OR b
   */
  public static long[] or(long[] a, long[] b) {
    int maxLen = Math.max(a.length, b.length);
    long[] result = new long[maxLen];
    for (int i = 0; i < maxLen; i++) {
      long aVal = i < a.length ? a[i] : 0L;
      long bVal = i < b.length ? b[i] : 0L;
      result[i] = aVal | bVal;
    }
    return result;
  }

  /**
   * Bitwise AND NOT of two bitmaps (a AND NOT b). Result stored in a new array.
   *
   * @param a first bitmap
   * @param b second bitmap (bits to exclude)
   * @return new bitmap containing a AND NOT b
   */
  public static long[] andNot(long[] a, long[] b) {
    long[] result = new long[a.length];
    int minLen = Math.min(a.length, b.length);
    for (int i = 0; i < minLen; i++) {
      result[i] = a[i] & ~b[i];
    }
    // Copy remaining words from a (no corresponding b words to exclude)
    for (int i = minLen; i < a.length; i++) {
      result[i] = a[i];
    }
    return result;
  }

  /**
   * Find the first set bit (lowest index).
   *
   * @param bitmap the bitmap to search
   * @return index of first set bit, or -1 if empty
   */
  public static int findFirst(long[] bitmap) {
    for (int i = 0; i < bitmap.length; i++) {
      if (bitmap[i] != 0L) {
        return i * 64 + Long.numberOfTrailingZeros(bitmap[i]);
      }
    }
    return -1;
  }

  /**
   * Check if bitmap is empty (no bits set).
   *
   * @param bitmap the bitmap to check
   * @return true if no bits are set
   */
  public static boolean isEmpty(long[] bitmap) {
    for (long word : bitmap) {
      if (word != 0L) {
        return false;
      }
    }
    return true;
  }

  /**
   * Check if a specific bit is set.
   *
   * @param bitmap the bitmap
   * @param index the bit index to check
   * @return true if bit is set
   */
  public static boolean isSet(long[] bitmap, int index) {
    int wordIndex = index / 64;
    if (wordIndex >= bitmap.length) {
      return false;
    }
    int bitIndex = index % 64;
    return (bitmap[wordIndex] & (1L << bitIndex)) != 0;
  }

  /**
   * Set a specific bit.
   *
   * @param bitmap the bitmap (modified in place)
   * @param index the bit index to set
   */
  public static void set(long[] bitmap, int index) {
    int wordIndex = index / 64;
    if (wordIndex < bitmap.length) {
      int bitIndex = index % 64;
      bitmap[wordIndex] |= (1L << bitIndex);
    }
  }

  /**
   * Count the number of set bits (population count).
   *
   * @param bitmap the bitmap
   * @return number of set bits
   */
  public static int cardinality(long[] bitmap) {
    int count = 0;
    for (long word : bitmap) {
      count += Long.bitCount(word);
    }
    return count;
  }

  /**
   * Clone a bitmap.
   *
   * @param bitmap the bitmap to clone
   * @return a new copy of the bitmap
   */
  public static long[] copy(long[] bitmap) {
    return bitmap.clone();
  }
}
