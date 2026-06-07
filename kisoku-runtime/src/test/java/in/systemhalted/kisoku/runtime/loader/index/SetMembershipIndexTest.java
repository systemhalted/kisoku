package in.systemhalted.kisoku.runtime.loader.index;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.runtime.csv.Operator;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SetMembershipIndex}. */
class SetMembershipIndexTest {

  /**
   * Creates a presence bitmap with MSB-first encoding.
   *
   * @param rowCount total rows
   * @param presentRows which rows have conditions (non-blank)
   * @return the presence bitmap
   */
  private byte[] createPresenceBitmap(int rowCount, int... presentRows) {
    int bitmapSize = (rowCount + 7) / 8;
    byte[] bitmap = new byte[bitmapSize];

    for (int row : presentRows) {
      int byteIndex = row / 8;
      int bitIndex = 7 - (row % 8); // MSB-first
      bitmap[byteIndex] |= (1 << bitIndex);
    }

    return bitmap;
  }

  // Test data: 5 rows with sets
  // Row 0: (10, 20, 30)
  // Row 1: (20, 40)
  // Row 2: blank (no condition)
  // Row 3: (30)
  // Row 4: blank (no condition)
  private static final int[] LIST_OFFSETS = {0, 3, 0, 5, 0};
  private static final short[] LIST_LENGTHS = {3, 2, 0, 1, 0};
  private static final int[] ALL_VALUES = {10, 20, 30, 20, 40, 30};
  private static final int ROW_COUNT = 5;

  private SetMembershipIndex buildIndex(Operator operator) {
    byte[] presence = createPresenceBitmap(ROW_COUNT, 0, 1, 3);
    return SetMembershipIndex.build(
        LIST_OFFSETS, LIST_LENGTHS, ALL_VALUES, presence, operator, ROW_COUNT);
  }

  @Test
  void buildCreatesInvertedIndexFromColumnData() {
    SetMembershipIndex index = buildIndex(Operator.IN);

    assertNotNull(index);
    assertEquals(4, index.uniqueValueCount()); // 10, 20, 30, 40
    assertTrue(index.memorySizeBytes() > 0);
  }

  @Test
  void inOperatorReturnsRowsContainingValuePlusBlanks() {
    SetMembershipIndex index = buildIndex(Operator.IN);

    // Value 20 appears in rows 0 and 1; rows 2 and 4 are blank (always match)
    long[] candidates = index.getCandidates(20);
    assertTrue(CandidateBitmap.isSet(candidates, 0));
    assertTrue(CandidateBitmap.isSet(candidates, 1));
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // blank
    assertFalse(CandidateBitmap.isSet(candidates, 3)); // set is (30)
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // blank
    assertEquals(4, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void inOperatorReturnsOnlyBlanksForUnknownValue() {
    SetMembershipIndex index = buildIndex(Operator.IN);

    long[] candidates = index.getCandidates(999);
    assertFalse(CandidateBitmap.isSet(candidates, 0));
    assertFalse(CandidateBitmap.isSet(candidates, 1));
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // blank
    assertFalse(CandidateBitmap.isSet(candidates, 3));
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // blank
    assertEquals(2, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void notInOperatorReturnsComplementPlusBlanks() {
    SetMembershipIndex index = buildIndex(Operator.NOT_IN);

    // Value 20 appears in rows 0 and 1 -> NOT_IN excludes them;
    // row 3 has a condition not containing 20 -> matches; blanks always match
    long[] candidates = index.getCandidates(20);
    assertFalse(CandidateBitmap.isSet(candidates, 0));
    assertFalse(CandidateBitmap.isSet(candidates, 1));
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // blank
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // (30) does not contain 20
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // blank
    assertEquals(3, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void notInOperatorReturnsAllRowsForUnknownValue() {
    SetMembershipIndex index = buildIndex(Operator.NOT_IN);

    // Unknown value is in no set -> every condition row matches, plus blanks
    long[] candidates = index.getCandidates(999);
    assertEquals(ROW_COUNT, CandidateBitmap.cardinality(candidates));
  }
}
