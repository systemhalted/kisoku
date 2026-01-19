package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;

/**
 * Decodes and matches set membership column data (IN, NOT_IN operators).
 *
 * <p>Format:
 *
 * <pre>
 * presence_bitmap (ceil(row_count/8) bytes)
 * list_offsets[row_count] (4 bytes each)
 * list_lengths[row_count] (2 bytes each)
 * all_values[] (4 bytes each)
 * </pre>
 */
final class SetMembershipColumnDecoder implements ColumnDecoder {
  private final ColumnDefinition column;
  private final byte[] presenceBitmap;
  private final int[] listOffsets;
  private final short[] listLengths;
  private final int[] allValues;
  private final StringDictionaryReader dictionary;

  private SetMembershipColumnDecoder(
      ColumnDefinition column,
      byte[] presenceBitmap,
      int[] listOffsets,
      short[] listLengths,
      int[] allValues,
      StringDictionaryReader dictionary) {
    this.column = column;
    this.presenceBitmap = presenceBitmap;
    this.listOffsets = listOffsets;
    this.listLengths = listLengths;
    this.allValues = allValues;
    this.dictionary = dictionary;
  }

  static SetMembershipColumnDecoder create(
      ColumnDefinition column, ByteBuffer data, int rowCount, StringDictionaryReader dictionary) {
    int bitmapSize = BitMapUtils.bitmapSize(rowCount);
    byte[] bitmap = new byte[bitmapSize];
    data.get(bitmap);

    int[] offsets = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      offsets[i] = data.getInt();
    }

    short[] lengths = new short[rowCount];
    for (int i = 0; i < rowCount; i++) {
      lengths[i] = data.getShort();
    }

    // Calculate total values count
    int totalValues = 0;
    for (int i = 0; i < rowCount; i++) {
      int end = offsets[i] + (lengths[i] & 0xFFFF);
      if (end > totalValues) {
        totalValues = end;
      }
    }

    int[] allValues = new int[totalValues];
    for (int i = 0; i < totalValues; i++) {
      allValues[i] = data.getInt();
    }

    return new SetMembershipColumnDecoder(column, bitmap, offsets, lengths, allValues, dictionary);
  }

  @Override
  public boolean matches(int rowIndex, Object inputValue) {
    if (!hasCondition(rowIndex)) {
      return true; // Blank = no condition, always matches
    }

    int offset = listOffsets[rowIndex];
    int length = listLengths[rowIndex] & 0xFFFF;
    int inputInt = TypeCoercion.toComparableInt(inputValue, column.type(), dictionary);

    boolean found = false;
    for (int i = 0; i < length; i++) {
      if (allValues[offset + i] == inputInt) {
        found = true;
        break;
      }
    }

    return (column.operator() == Operator.IN) ? found : !found;
  }

  @Override
  public boolean hasCondition(int rowIndex) {
    return BitMapUtils.isPresent(presenceBitmap, rowIndex);
  }

  @Override
  public Object getValue(int rowIndex) {
    // Set membership columns are input-only, not used for output
    return null;
  }
}
