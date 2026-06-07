package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Decodes and matches set membership column data (IN, NOT_IN operators).
 *
 * <p>Format (within the column's slice of the artifact buffer):
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * list_offsets[row_count] (4 bytes each)
 * list_lengths[row_count] (2 bytes each)
 * all_values[] (4 bytes each)
 * </pre>
 *
 * <p>Values are read lazily through the buffer using absolute offsets (no on-heap copy, position
 * independent, safe for concurrent evaluation).
 */
final class SetMembershipColumnDecoder implements ColumnDecoder {
  private final ColumnDefinition column;
  private final ByteBuffer buffer;
  private final int bitmapBase;
  private final int offsetsBase;
  private final int lengthsBase;
  private final int valuesBase;
  private final int rowCount;
  private final StringDictionaryReader dictionary;

  private SetMembershipColumnDecoder(
      ColumnDefinition column,
      ByteBuffer buffer,
      int bitmapBase,
      int offsetsBase,
      int lengthsBase,
      int valuesBase,
      int rowCount,
      StringDictionaryReader dictionary) {
    this.column = column;
    this.buffer = buffer;
    this.bitmapBase = bitmapBase;
    this.offsetsBase = offsetsBase;
    this.lengthsBase = lengthsBase;
    this.valuesBase = valuesBase;
    this.rowCount = rowCount;
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
  static SetMembershipColumnDecoder create(
      ColumnDefinition column,
      ByteBuffer buffer,
      int base,
      int rowCount,
      StringDictionaryReader dictionary) {
    int offsetsBase = base + BitMapUtils.bitmapSize(rowCount);
    int lengthsBase = offsetsBase + rowCount * 4;
    int valuesBase = lengthsBase + rowCount * 2;
    return new SetMembershipColumnDecoder(
        column, buffer, base, offsetsBase, lengthsBase, valuesBase, rowCount, dictionary);
  }

  private int listOffset(int rowIndex) {
    return buffer.getInt(offsetsBase + rowIndex * 4);
  }

  private int listLength(int rowIndex) {
    return buffer.getShort(lengthsBase + rowIndex * 2) & 0xFFFF;
  }

  private int setValue(int index) {
    return buffer.getInt(valuesBase + index * 4);
  }

  @Override
  public boolean matches(int rowIndex, Object inputValue) {
    if (!hasCondition(rowIndex)) {
      return true; // Blank = no condition, always matches
    }

    int offset = listOffset(rowIndex);
    int length = listLength(rowIndex);
    int inputInt = TypeCoercion.toComparableInt(inputValue, column.type(), dictionary);

    boolean found = false;
    for (int i = 0; i < length; i++) {
      if (setValue(offset + i) == inputInt) {
        found = true;
        break;
      }
    }

    return (column.operator() == Operator.IN) ? found : !found;
  }

  @Override
  public boolean hasCondition(int rowIndex) {
    return BitMapUtils.isPresent(buffer, bitmapBase, rowIndex);
  }

  @Override
  public Object getValue(int rowIndex) {
    // Set membership columns are input-only, not used for output
    return null;
  }

  // Package-private accessors for index building. These materialize the column's raw data from the
  // buffer on demand; they are used once at load time and the arrays are not retained.

  byte[] presenceBitmap() {
    int size = BitMapUtils.bitmapSize(rowCount);
    byte[] out = new byte[size];
    for (int i = 0; i < size; i++) {
      out[i] = buffer.get(bitmapBase + i);
    }
    return out;
  }

  int[] listOffsets() {
    int[] out = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      out[i] = listOffset(i);
    }
    return out;
  }

  short[] listLengths() {
    short[] out = new short[rowCount];
    for (int i = 0; i < rowCount; i++) {
      out[i] = buffer.getShort(lengthsBase + i * 2);
    }
    return out;
  }

  int[] allValues() {
    int total = totalValues();
    int[] out = new int[total];
    for (int i = 0; i < total; i++) {
      out[i] = setValue(i);
    }
    return out;
  }

  /** Number of entries in the packed all_values array (max offset+length across rows). */
  private int totalValues() {
    int total = 0;
    for (int i = 0; i < rowCount; i++) {
      int end = listOffset(i) + listLength(i);
      if (end > total) {
        total = end;
      }
    }
    return total;
  }
}
