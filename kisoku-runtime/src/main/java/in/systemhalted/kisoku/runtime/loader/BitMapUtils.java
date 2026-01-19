package in.systemhalted.kisoku.runtime.loader;

/** Utility methods for presence bitmap operations. */
final class BitMapUtils {
  private BitMapUtils() {}

  /**
   * Check if bit at rowIndex is set (MSB-first, matching encoder).
   *
   * @param bitmap the presence bitmap
   * @param rowIndex the row index to check
   * @return true if the bit is set
   */
  static boolean isPresent(byte[] bitmap, int rowIndex) {
    int byteIndex = rowIndex / 8;
    int bitIndex = 7 - (rowIndex % 8); // MSB-first
    return (bitmap[byteIndex] & (1 << bitIndex)) != 0;
  }

  /**
   * Calculate bitmap size for given row count.
   *
   * @param rowCount number of rows
   * @return number of bytes needed for the bitmap
   */
  static int bitmapSize(int rowCount) {
    return (rowCount + 7) / 8;
  }
}
