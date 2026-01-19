package in.systemhalted.kisoku.runtime.loader.index;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link EqualityIndex}. */
class EqualityIndexTest {

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

  @Test
  void buildCreatesIndexFromColumnData() {
    int rowCount = 10;
    int[] values = {100, 200, 100, 300, 200, 100, 0, 0, 400, 100};
    // Rows 0-5 and 8-9 have conditions, rows 6-7 are blank
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4, 5, 8, 9);

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    assertNotNull(index);
    assertEquals(4, index.uniqueValueCount()); // 100, 200, 300, 400
  }

  @Test
  void getCandidatesReturnsMatchingRowsAndBlanks() {
    int rowCount = 10;
    // Values: rows 0,2,5,9 = 100; rows 1,4 = 200; row 3 = 300; row 8 = 400
    // Rows 6,7 are blank (no condition)
    int[] values = {100, 200, 100, 300, 200, 100, 0, 0, 400, 100};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4, 5, 8, 9);

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    // Search for 100: should return rows 0,2,5,9 (exact match) + rows 6,7 (blank)
    long[] candidates100 = index.getCandidates(100);
    assertTrue(CandidateBitmap.isSet(candidates100, 0));
    assertTrue(CandidateBitmap.isSet(candidates100, 2));
    assertTrue(CandidateBitmap.isSet(candidates100, 5));
    assertTrue(CandidateBitmap.isSet(candidates100, 9));
    assertTrue(CandidateBitmap.isSet(candidates100, 6)); // blank
    assertTrue(CandidateBitmap.isSet(candidates100, 7)); // blank
    assertFalse(CandidateBitmap.isSet(candidates100, 1)); // has 200
    assertFalse(CandidateBitmap.isSet(candidates100, 3)); // has 300
    assertFalse(CandidateBitmap.isSet(candidates100, 4)); // has 200
    assertFalse(CandidateBitmap.isSet(candidates100, 8)); // has 400

    // Search for 200: should return rows 1,4 + blanks 6,7
    long[] candidates200 = index.getCandidates(200);
    assertTrue(CandidateBitmap.isSet(candidates200, 1));
    assertTrue(CandidateBitmap.isSet(candidates200, 4));
    assertTrue(CandidateBitmap.isSet(candidates200, 6));
    assertTrue(CandidateBitmap.isSet(candidates200, 7));
    assertEquals(4, CandidateBitmap.cardinality(candidates200));
  }

  @Test
  void getCandidatesReturnsOnlyBlanksForUnknownValue() {
    int rowCount = 10;
    int[] values = {100, 200, 100, 300, 200, 100, 0, 0, 400, 100};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4, 5, 8, 9);

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    // Search for 999 (not in data): should only return blank rows 6,7
    long[] candidates = index.getCandidates(999);
    assertEquals(2, CandidateBitmap.cardinality(candidates));
    assertTrue(CandidateBitmap.isSet(candidates, 6));
    assertTrue(CandidateBitmap.isSet(candidates, 7));
  }

  @Test
  void handleAllRowsWithConditions() {
    int rowCount = 5;
    int[] values = {10, 20, 30, 10, 20};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4); // All have conditions

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    // Search for 10: should return rows 0,3 only (no blanks)
    long[] candidates = index.getCandidates(10);
    assertEquals(2, CandidateBitmap.cardinality(candidates));
    assertTrue(CandidateBitmap.isSet(candidates, 0));
    assertTrue(CandidateBitmap.isSet(candidates, 3));

    // Search for unknown: should return empty (no blanks)
    long[] unknown = index.getCandidates(999);
    assertTrue(CandidateBitmap.isEmpty(unknown));
  }

  @Test
  void handleAllRowsBlank() {
    int rowCount = 5;
    int[] values = {0, 0, 0, 0, 0}; // Values don't matter when all blank
    byte[] presence = new byte[(rowCount + 7) / 8]; // All zeros = all blank

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    assertEquals(0, index.uniqueValueCount()); // No indexed values

    // Any search should return all rows (all blank = all match)
    long[] candidates = index.getCandidates(123);
    assertEquals(5, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void memorySizeBytesReturnsReasonableEstimate() {
    int rowCount = 1000;
    int[] values = new int[rowCount];
    for (int i = 0; i < rowCount; i++) {
      values[i] = i % 10; // 10 unique values
    }
    byte[] presence = new byte[(rowCount + 7) / 8];
    java.util.Arrays.fill(presence, (byte) 0xFF); // All present

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    long memSize = index.memorySizeBytes();

    // Should be roughly: 1 noCondition bitmap + 10 value bitmaps + HashMap overhead
    // Each bitmap: ceil(1000/64) = 16 longs = 128 bytes
    // Expected: 11 * 128 + 10 * 48 = 1408 + 480 = 1888 bytes
    assertTrue(memSize > 1000, "Memory size should be reasonable: " + memSize);
    assertTrue(memSize < 10000, "Memory size should not be excessive: " + memSize);
  }

  @Test
  void handlesSingleRow() {
    int rowCount = 1;
    int[] values = {42};
    byte[] presence = createPresenceBitmap(rowCount, 0);

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    long[] candidates42 = index.getCandidates(42);
    assertEquals(1, CandidateBitmap.cardinality(candidates42));
    assertTrue(CandidateBitmap.isSet(candidates42, 0));

    long[] candidatesOther = index.getCandidates(99);
    assertTrue(CandidateBitmap.isEmpty(candidatesOther));
  }

  @Test
  void handlesLargeRowCountWithManyUniqueValues() {
    int rowCount = 10000;
    int[] values = new int[rowCount];
    byte[] presence = new byte[(rowCount + 7) / 8];
    java.util.Arrays.fill(presence, (byte) 0xFF);

    // 100 unique values, evenly distributed
    for (int i = 0; i < rowCount; i++) {
      values[i] = i % 100;
    }

    EqualityIndex index = EqualityIndex.build(values, presence, rowCount);

    assertEquals(100, index.uniqueValueCount());

    // Each value should appear in rowCount/100 = 100 rows
    long[] candidates = index.getCandidates(50);
    assertEquals(100, CandidateBitmap.cardinality(candidates));
  }
}
