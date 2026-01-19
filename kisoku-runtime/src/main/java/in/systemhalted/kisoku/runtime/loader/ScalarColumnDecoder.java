package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Decodes and matches scalar column data (single value per row).
 *
 * <p>Handles operators: RULE_ID, PRIORITY, SET, EQ, NE, GT, GTE, LT, LTE
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * values[row_count] (4 bytes each)
 * </pre>
 */
final class ScalarColumnDecoder implements ColumnDecoder {
  private final ColumnDefinition column;
  private final byte[] presenceBitmap;
  private final int[] values;
  private final StringDictionaryReader dictionary;

  private ScalarColumnDecoder(
      ColumnDefinition column,
      byte[] presenceBitmap,
      int[] values,
      StringDictionaryReader dictionary) {
    this.column = column;
    this.presenceBitmap = presenceBitmap;
    this.values = values;
    this.dictionary = dictionary;
  }

  static ScalarColumnDecoder create(
      ColumnDefinition column, ByteBuffer data, int rowCount, StringDictionaryReader dictionary) {
    int bitmapSize = BitMapUtils.bitmapSize(rowCount);
    byte[] bitmap = new byte[bitmapSize];
    data.get(bitmap);

    int[] values = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      values[i] = data.getInt();
    }

    return new ScalarColumnDecoder(column, bitmap, values, dictionary);
  }

  @Override
  public boolean matches(int rowIndex, Object inputValue) {
    if (!hasCondition(rowIndex)) {
      return true; // Blank = no condition, always matches
    }

    int storedValue = values[rowIndex];
    int inputInt = TypeCoercion.toComparableInt(inputValue, column.type(), dictionary);

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
    return BitMapUtils.isPresent(presenceBitmap, rowIndex);
  }

  @Override
  public Object getValue(int rowIndex) {
    if (!hasCondition(rowIndex)) {
      return null;
    }
    int storedValue = values[rowIndex];
    return TypeCoercion.decodeValue(storedValue, column.type(), dictionary);
  }
}
