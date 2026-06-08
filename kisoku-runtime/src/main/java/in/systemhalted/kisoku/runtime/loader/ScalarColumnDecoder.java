package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Decodes and matches scalar column data (single value per row).
 *
 * <p>Handles operators: RULE_ID, PRIORITY, SET, EQ, NE, GT, GTE, LT, LTE
 *
 * <p>Format (within the column's slice of the artifact buffer):
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * values[row_count] (4 bytes each)
 * </pre>
 *
 * <p>Values are read lazily through the buffer using absolute offsets so the column data stays in
 * the (possibly off-heap or memory-mapped) buffer rather than being copied onto the heap. Absolute
 * reads do not touch the buffer's position, so a shared buffer is safe for concurrent evaluation.
 */
final class ScalarColumnDecoder implements ColumnDecoder {
  private final ColumnDefinition column;
  private final ByteBuffer buffer;
  private final int bitmapBase;
  private final int valuesBase;
  private final int rowCount;
  private final StringDictionaryReader dictionary;

  private ScalarColumnDecoder(
      ColumnDefinition column,
      ByteBuffer buffer,
      int bitmapBase,
      int valuesBase,
      int rowCount,
      StringDictionaryReader dictionary) {
    this.column = column;
    this.buffer = buffer;
    this.bitmapBase = bitmapBase;
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
  static ScalarColumnDecoder create(
      ColumnDefinition column,
      ByteBuffer buffer,
      int base,
      int rowCount,
      StringDictionaryReader dictionary) {
    int valuesBase = base + BitMapUtils.bitmapSize(rowCount);
    return new ScalarColumnDecoder(column, buffer, base, valuesBase, rowCount, dictionary);
  }

  private int valueAt(int rowIndex) {
    return buffer.getInt(valuesBase + rowIndex * 4);
  }

  @Override
  public boolean matches(int rowIndex, Object inputValue) {
    if (!hasCondition(rowIndex)) {
      return true; // Blank = no condition, always matches
    }
    return matchesCoerced(
        rowIndex, TypeCoercion.toComparableInt(inputValue, column.type(), dictionary));
  }

  @Override
  public boolean matchesCoerced(int rowIndex, int inputInt) {
    if (!hasCondition(rowIndex)) {
      return true; // Blank = no condition, always matches
    }

    int storedValue = valueAt(rowIndex);

    Operator op = column.operator();
    return switch (op) {
      case EQ, SET, RULE_ID, PRIORITY -> storedValue == inputInt;
      case NE -> storedValue != inputInt;
      case GT -> inputInt > storedValue;
      case GTE -> inputInt >= storedValue;
      case LT -> inputInt < storedValue;
      case LTE -> inputInt <= storedValue;
      default -> throw new IllegalStateException("Unexpected operator: " + op);
    };
  }

  @Override
  public boolean hasCondition(int rowIndex) {
    return BitMapUtils.isPresent(buffer, bitmapBase, rowIndex);
  }

  @Override
  public Object getValue(int rowIndex) {
    if (!hasCondition(rowIndex)) {
      return null;
    }
    return TypeCoercion.decodeValue(valueAt(rowIndex), column.type(), dictionary);
  }

  // Package-private accessors for index building. These materialize the column's raw data from the
  // buffer on demand; they are used once at load time and the arrays are not retained.

  /**
   * Materializes the values array for index building.
   *
   * @return the values array (one int per row)
   */
  int[] values() {
    int[] out = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      out[i] = valueAt(i);
    }
    return out;
  }

  /**
   * Materializes the presence bitmap for index building.
   *
   * @return the presence bitmap (MSB-first encoding)
   */
  byte[] presenceBitmap() {
    int size = BitMapUtils.bitmapSize(rowCount);
    byte[] out = new byte[size];
    for (int i = 0; i < size; i++) {
      out[i] = buffer.get(bitmapBase + i);
    }
    return out;
  }
}
