package in.systemhalted.kisoku.runtime.loader;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.api.ColumnType;
import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * Verifies the decoders read column data through the buffer using absolute offsets, including when
 * the column starts at a non-zero base and the presence bitmap leaves the value array unaligned.
 */
class LazyDecoderTest {

  private static final int ROW_COUNT = 10;

  // A non-zero, non-4-aligned base so getInt() lands on unaligned byte offsets.
  private static final int BASE = 5;

  private StringDictionaryReader emptyDictionary() {
    ByteBuffer dict = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
    dict.putInt(0); // entry_count = 0
    dict.flip();
    return StringDictionaryReader.read(dict, 0);
  }

  private void writeBitmap(ByteBuffer buf, int base, int... presentRows) {
    int bitmapSize = BitMapUtils.bitmapSize(ROW_COUNT);
    for (int row : presentRows) {
      int byteIndex = base + row / 8;
      int bitIndex = 7 - (row % 8); // MSB-first
      buf.put(byteIndex, (byte) (buf.get(byteIndex) | (1 << bitIndex)));
    }
    // touch bitmapSize to document intent; presence bytes already zero-initialized
    assertTrue(bitmapSize >= 1);
  }

  private ColumnDefinition intColumn(Operator op) {
    return new ColumnDefinition(1, "AGE", op, ColumnType.INTEGER, ColumnRole.INPUT, 0x01, 0);
  }

  @Test
  void scalarDecoderReadsValuesAtAbsoluteOffset() {
    int bitmapSize = BitMapUtils.bitmapSize(ROW_COUNT);
    ByteBuffer buf =
        ByteBuffer.allocate(BASE + bitmapSize + ROW_COUNT * 4).order(ByteOrder.BIG_ENDIAN);

    // Rows 0,1,3,9 have conditions; the rest are blank.
    writeBitmap(buf, BASE, 0, 1, 3, 9);
    int[] values = {30, 40, 0, 65, 0, 0, 0, 0, 0, 18};
    for (int i = 0; i < ROW_COUNT; i++) {
      buf.putInt(BASE + bitmapSize + i * 4, values[i]);
    }

    ScalarColumnDecoder decoder =
        ScalarColumnDecoder.create(
            intColumn(Operator.GTE), buf, BASE, ROW_COUNT, emptyDictionary());

    // GTE: input >= stored
    assertTrue(decoder.matches(0, 30)); // 30 >= 30
    assertFalse(decoder.matches(0, 29)); // 29 < 30
    assertTrue(decoder.matches(3, 100)); // 100 >= 65
    // Blank rows always match regardless of input.
    assertTrue(decoder.matches(4, -999));
    assertFalse(decoder.hasCondition(4));
    assertTrue(decoder.hasCondition(9));
    assertEquals(18, decoder.getValue(9));
    assertNull(decoder.getValue(4));
  }

  @Test
  void rangeDecoderReadsMinMaxAtAbsoluteOffset() {
    int bitmapSize = BitMapUtils.bitmapSize(ROW_COUNT);
    ByteBuffer buf =
        ByteBuffer.allocate(BASE + bitmapSize + ROW_COUNT * 4 * 2).order(ByteOrder.BIG_ENDIAN);

    writeBitmap(buf, BASE, 0, 2);
    int minBase = BASE + bitmapSize;
    int maxBase = minBase + ROW_COUNT * 4;
    buf.putInt(minBase + 0 * 4, 18);
    buf.putInt(maxBase + 0 * 4, 65);
    buf.putInt(minBase + 2 * 4, 21);
    buf.putInt(maxBase + 2 * 4, 30);

    RangeColumnDecoder decoder =
        RangeColumnDecoder.create(
            intColumn(Operator.BETWEEN_INCLUSIVE), buf, BASE, ROW_COUNT, emptyDictionary());

    assertTrue(decoder.matches(0, 18)); // inclusive lower bound
    assertTrue(decoder.matches(0, 65)); // inclusive upper bound
    assertFalse(decoder.matches(0, 66));
    assertTrue(decoder.matches(2, 25));
    assertFalse(decoder.matches(2, 31));
    assertTrue(decoder.matches(5, 9999)); // blank row matches
  }

  @Test
  void setDecoderReadsListsAtAbsoluteOffset() {
    int bitmapSize = BitMapUtils.bitmapSize(ROW_COUNT);
    // Row 0 set = {10,20,30}; row 1 set = {40}; rest blank. all_values length = 4.
    int totalValues = 4;
    ByteBuffer buf =
        ByteBuffer.allocate(BASE + bitmapSize + ROW_COUNT * 4 + ROW_COUNT * 2 + totalValues * 4)
            .order(ByteOrder.BIG_ENDIAN);

    writeBitmap(buf, BASE, 0, 1);
    int offsetsBase = BASE + bitmapSize;
    int lengthsBase = offsetsBase + ROW_COUNT * 4;
    int valuesBase = lengthsBase + ROW_COUNT * 2;

    buf.putInt(offsetsBase + 0 * 4, 0); // row 0 starts at 0
    buf.putShort(lengthsBase + 0 * 2, (short) 3); // length 3
    buf.putInt(offsetsBase + 1 * 4, 3); // row 1 starts at 3
    buf.putShort(lengthsBase + 1 * 2, (short) 1); // length 1
    int[] all = {10, 20, 30, 40};
    for (int i = 0; i < all.length; i++) {
      buf.putInt(valuesBase + i * 4, all[i]);
    }

    SetMembershipColumnDecoder decoder =
        SetMembershipColumnDecoder.create(
            intColumn(Operator.IN), buf, BASE, ROW_COUNT, emptyDictionary());

    assertTrue(decoder.matches(0, 20)); // 20 in {10,20,30}
    assertFalse(decoder.matches(0, 99));
    assertTrue(decoder.matches(1, 40));
    assertFalse(decoder.matches(1, 10));
    assertTrue(decoder.matches(7, 12345)); // blank row matches
  }

  @Test
  void matchesCoercedAgreesWithMatchesForIntegerColumns() {
    int bitmapSize = BitMapUtils.bitmapSize(ROW_COUNT);
    ByteBuffer buf =
        ByteBuffer.allocate(BASE + bitmapSize + ROW_COUNT * 4).order(ByteOrder.BIG_ENDIAN);
    writeBitmap(buf, BASE, 0, 1, 3, 9);
    int[] values = {30, 40, 0, 65, 0, 0, 0, 0, 0, 18};
    for (int i = 0; i < ROW_COUNT; i++) {
      buf.putInt(BASE + bitmapSize + i * 4, values[i]);
    }

    ScalarColumnDecoder decoder =
        ScalarColumnDecoder.create(
            intColumn(Operator.GTE), buf, BASE, ROW_COUNT, emptyDictionary());

    // For INTEGER columns the coerced int is the raw value, so matchesCoerced must mirror matches.
    for (int row = 0; row < ROW_COUNT; row++) {
      for (int v : new int[] {17, 18, 30, 65, 100}) {
        assertEquals(
            decoder.matches(row, v), decoder.matchesCoerced(row, v), "row " + row + " value " + v);
      }
    }
  }
}
