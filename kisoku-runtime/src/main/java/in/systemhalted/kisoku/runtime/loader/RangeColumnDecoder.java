package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Decodes and matches range column data (min and max values per row).
 *
 * <p>Handles operators: BETWEEN_INCLUSIVE, BETWEEN_EXCLUSIVE, NOT_BETWEEN_INCLUSIVE,
 * NOT_BETWEEN_EXCLUSIVE
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * min_values[row_count] (4 bytes each)
 * max_values[row_count] (4 bytes each)
 * </pre>
 */
final class RangeColumnDecoder implements ColumnDecoder {
  private final ColumnDefinition column;
  private final byte[] presenceBitmap;
  private final int[] minValues;
  private final int[] maxValues;
  private final StringDictionaryReader dictionary;

  private RangeColumnDecoder(
      ColumnDefinition column,
      byte[] presenceBitmap,
      int[] minValues,
      int[] maxValues,
      StringDictionaryReader dictionary) {
    this.column = column;
    this.presenceBitmap = presenceBitmap;
    this.minValues = minValues;
    this.maxValues = maxValues;
    this.dictionary = dictionary;
  }

  static RangeColumnDecoder create(
      ColumnDefinition column, ByteBuffer data, int rowCount, StringDictionaryReader dictionary) {
    int bitmapSize = BitMapUtils.bitmapSize(rowCount);
    byte[] bitmap = new byte[bitmapSize];
    data.get(bitmap);

    int[] minValues = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      minValues[i] = data.getInt();
    }

    int[] maxValues = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      maxValues[i] = data.getInt();
    }

    return new RangeColumnDecoder(column, bitmap, minValues, maxValues, dictionary);
  }

  @Override
  public boolean matches(int rowIndex, Object inputValue) {
    if (!hasCondition(rowIndex)) {
      return true; // Blank = no condition, always matches
    }

    int min = minValues[rowIndex];
    int max = maxValues[rowIndex];
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
    return BitMapUtils.isPresent(presenceBitmap, rowIndex);
  }

  @Override
  public Object getValue(int rowIndex) {
    // Range columns are input-only, not used for output
    return null;
  }
}
