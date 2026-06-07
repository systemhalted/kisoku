package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Decodes and matches range column data (min and max values per row).
 *
 * <p>Handles operators: BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE,
 * NOT_BETWEEN_EXCLUSIVE
 *
 * <p>Format (within the column's slice of the artifact buffer):
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * min_values[row_count] (4 bytes each)
 * max_values[row_count] (4 bytes each)
 * </pre>
 *
 * <p>Values are read lazily through the buffer using absolute offsets (no on-heap copy, position
 * independent, safe for concurrent evaluation).
 */
final class RangeColumnDecoder implements ColumnDecoder {
  private final ColumnDefinition column;
  private final ByteBuffer buffer;
  private final int bitmapBase;
  private final int minBase;
  private final int maxBase;
  private final StringDictionaryReader dictionary;

  private RangeColumnDecoder(
      ColumnDefinition column,
      ByteBuffer buffer,
      int bitmapBase,
      int minBase,
      int maxBase,
      StringDictionaryReader dictionary) {
    this.column = column;
    this.buffer = buffer;
    this.bitmapBase = bitmapBase;
    this.minBase = minBase;
    this.maxBase = maxBase;
    this.dictionary = dictionary;
  }

  /**
   * Creates a decoder over the column's data located at an absolute byte offset in the buffer.
   *
   * @param column the column definition
   * @param buffer the artifact buffer
   * @param base absolute byte offset of this column's data
   * @param rowCount number of rows
   * @param dictionary the string dictionary
   */
  static RangeColumnDecoder create(
      ColumnDefinition column,
      ByteBuffer buffer,
      int base,
      int rowCount,
      StringDictionaryReader dictionary) {
    int minBase = base + BitMapUtils.bitmapSize(rowCount);
    int maxBase = minBase + rowCount * 4;
    return new RangeColumnDecoder(column, buffer, base, minBase, maxBase, dictionary);
  }

  @Override
  public boolean matches(int rowIndex, Object inputValue) {
    if (!hasCondition(rowIndex)) {
      return true; // Blank = no condition, always matches
    }

    int min = buffer.getInt(minBase + rowIndex * 4);
    int max = buffer.getInt(maxBase + rowIndex * 4);
    int inputInt = TypeCoercion.toComparableInt(inputValue, column.type(), dictionary);

    Operator op = column.operator();
    return switch (op) {
      case BETWEEN_INCLUSIVE -> inputInt >= min && inputInt <= max;
      case BETWEEN_EXCLUSIVE -> inputInt > min && inputInt < max;
      case NOT_BETWEEN_INCLUSIVE -> inputInt < min || inputInt > max;
      case NOT_BETWEEN_EXCLUSIVE -> inputInt <= min || inputInt >= max;
      default -> throw new IllegalStateException("Unexpected operator: " + op);
    };
  }

  @Override
  public boolean hasCondition(int rowIndex) {
    return BitMapUtils.isPresent(buffer, bitmapBase, rowIndex);
  }

  @Override
  public Object getValue(int rowIndex) {
    // Range columns are input-only, not used for output
    return null;
  }
}
