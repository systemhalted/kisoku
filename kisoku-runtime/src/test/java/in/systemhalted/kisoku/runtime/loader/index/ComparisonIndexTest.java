package in.systemhalted.kisoku.runtime.loader.index;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.runtime.csv.Operator;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ComparisonIndex}. */
class ComparisonIndexTest {

  // ============================================================
  // Basic Operator Tests
  // ============================================================

  @Test
  void gtReturnsRowsWithValuesGreaterThanInput() {

    // Test setup: 5 unique values {10, 20, 30, 40, 50}, each in its own row (0-4)
    // Row 5 is blank (no condition)
    // For GT operator with input=30, should match rows with values > 30
    // Expected: rows 3 (value=40), row 4 (value=50), and row 5 (blank)
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }
    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    // GT 30: should return rows 3,4 (values > 30) + row 5 (blank)
    long[] candidates = index.getCandidates(30);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // value 10
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // value 20
    assertFalse(CandidateBitmap.isSet(candidates, 2)); // value 30 (equal)
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // value 40
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // value 50
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(3, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void gteReturnsRowsWithValuesGreaterOrEqual() {
    // Values: row 0=10, row 1=20, row 2=30, row 3=40, row 4=50
    // Row 5 is blank
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GTE, blankRowBitmap);

    // GTE 30: should return rows 2,3,4 (values >= 30) + row 5 (blank)
    long[] candidates = index.getCandidates(30);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // value 10
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // value 20
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // value 30 (equal)
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // value 40
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // value 50
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(4, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void ltReturnsRowsWithValuesLessThanInput() {
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);

    // LT 30: should return rows 0,1 (values < 30) + row 5 (blank)
    long[] candidates = index.getCandidates(30);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // value 10
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // value 20
    assertFalse(CandidateBitmap.isSet(candidates, 2)); // value 30 (not less than)
    assertFalse(CandidateBitmap.isSet(candidates, 3)); // value 40
    assertFalse(CandidateBitmap.isSet(candidates, 4)); // value 50
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(3, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void lteReturnsRowsWithValuesLessOrEqual() {
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LTE, blankRowBitmap);

    // LTE 30: should return rows 0,1,2 (values <= 30) + row 5 (blank)
    long[] candidates = index.getCandidates(30);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // value 10
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // value 20
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // value 30 (equal)
    assertFalse(CandidateBitmap.isSet(candidates, 3)); // value 40
    assertFalse(CandidateBitmap.isSet(candidates, 4)); // value 50
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(4, CandidateBitmap.cardinality(candidates));
  }

  // ============================================================
  // Blank Row Handling
  // ============================================================

  @Test
  void blankRowsAlwaysIncludedInResults() {
    int[] sortedValues = {100, 200, 300};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    CandidateBitmap.set(rowBitmaps[0], 0); // row 0 has value 100
    CandidateBitmap.set(rowBitmaps[1], 1); // row 1 has value 200
    CandidateBitmap.set(rowBitmaps[2], 2); // row 2 has value 300

    // Rows 3, 4, 5 are blank
    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 3);
    CandidateBitmap.set(blankRowBitmap, 4);
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    // GT 150: matches value 200, 300 + all blank rows
    long[] candidates = index.getCandidates(150);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // value 100, not > 150
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // value 200 > 150
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // value 300 > 150
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // blank
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // blank
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(5, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void onlyBlankRowsWhenNoValuesMatch() {
    int[] sortedValues = {10, 20, 30};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    for (int i = 0; i < 3; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 3);
    CandidateBitmap.set(blankRowBitmap, 4);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    // GT 100: no values > 100, should only return blanks
    long[] candidates = index.getCandidates(100);

    assertFalse(CandidateBitmap.isSet(candidates, 0));
    assertFalse(CandidateBitmap.isSet(candidates, 1));
    assertFalse(CandidateBitmap.isSet(candidates, 2));
    assertTrue(CandidateBitmap.isSet(candidates, 3));
    assertTrue(CandidateBitmap.isSet(candidates, 4));
    assertEquals(2, CandidateBitmap.cardinality(candidates));
  }

  // ============================================================
  // Edge Cases
  // ============================================================

  @Test
  void emptyIndexReturnsOnlyBlankRows() {
    int[] sortedValues = {};
    long[][] rowBitmaps = new long[0][1];

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 0);
    CandidateBitmap.set(blankRowBitmap, 1);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    long[] candidates = index.getCandidates(50);

    assertEquals(2, CandidateBitmap.cardinality(candidates));
    assertTrue(CandidateBitmap.isSet(candidates, 0));
    assertTrue(CandidateBitmap.isSet(candidates, 1));
  }

  @Test
  void inputValueNotInSortedValues() {
    // Values: 10, 30, 50 (no 20, 40)
    int[] sortedValues = {10, 30, 50};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    CandidateBitmap.set(rowBitmaps[0], 0);
    CandidateBitmap.set(rowBitmaps[1], 1);
    CandidateBitmap.set(rowBitmaps[2], 2);

    long[] blankRowBitmap = new long[1];

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    // GT 25: should match values 30, 50 (rows 1, 2)
    long[] candidates = index.getCandidates(25);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // value 10
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // value 30
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // value 50
    assertEquals(2, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void inputBelowAllValues() {
    int[] sortedValues = {100, 200, 300};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    for (int i = 0; i < 3; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];

    // GT: input below all values, all should match
    ComparisonIndex gtIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);
    long[] gtCandidates = gtIndex.getCandidates(50);
    assertEquals(3, CandidateBitmap.cardinality(gtCandidates));

    // LT: input below all values, none should match
    ComparisonIndex ltIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);
    long[] ltCandidates = ltIndex.getCandidates(50);
    assertTrue(CandidateBitmap.isEmpty(ltCandidates));
  }

  @Test
  void inputAboveAllValues() {
    int[] sortedValues = {100, 200, 300};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    for (int i = 0; i < 3; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];

    // GT: input above all values, none should match
    ComparisonIndex gtIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);
    long[] gtCandidates = gtIndex.getCandidates(500);
    assertTrue(CandidateBitmap.isEmpty(gtCandidates));

    // LT: input above all values, all should match
    ComparisonIndex ltIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);
    long[] ltCandidates = ltIndex.getCandidates(500);
    assertEquals(3, CandidateBitmap.cardinality(ltCandidates));
  }

  @Test
  void singleValueIndex() {
    int[] sortedValues = {50};
    long[][] rowBitmaps = createRowBitmaps(1, 1);
    CandidateBitmap.set(rowBitmaps[0], 0);

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 1);

    // GT 50: value 50 is not > 50, only blank matches
    ComparisonIndex gtIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);
    long[] gtCandidates = gtIndex.getCandidates(50);
    assertEquals(1, CandidateBitmap.cardinality(gtCandidates));
    assertTrue(CandidateBitmap.isSet(gtCandidates, 1)); // blank row

    // GTE 50: value 50 is >= 50, plus blank
    ComparisonIndex gteIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GTE, blankRowBitmap);
    long[] gteCandidates = gteIndex.getCandidates(50);
    assertEquals(2, CandidateBitmap.cardinality(gteCandidates));
    assertTrue(CandidateBitmap.isSet(gteCandidates, 0)); // value row
    assertTrue(CandidateBitmap.isSet(gteCandidates, 1)); // blank row

    // LT 50: value 50 is not < 50, only blank matches
    ComparisonIndex ltIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);
    long[] ltCandidates = ltIndex.getCandidates(50);
    assertEquals(1, CandidateBitmap.cardinality(ltCandidates));
    assertTrue(CandidateBitmap.isSet(ltCandidates, 1)); // blank row

    // LTE 50: value 50 is <= 50, plus blank
    ComparisonIndex lteIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LTE, blankRowBitmap);
    long[] lteCandidates = lteIndex.getCandidates(50);
    assertEquals(2, CandidateBitmap.cardinality(lteCandidates));
    assertTrue(CandidateBitmap.isSet(lteCandidates, 0)); // value row
    assertTrue(CandidateBitmap.isSet(lteCandidates, 1)); // blank row
  }

  // ============================================================
  // Memory Tracking
  // ============================================================

  @Test
  void memorySizeBytesCalculatesCorrectly() {
    // 5 values, each bitmap has 2 longs
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 2);
    long[] blankRowBitmap = new long[2];

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    long memSize = index.memorySizeBytes();

    // Expected:
    // sortedValues: 5 * 4 = 20 bytes
    // rowBitmaps: 5 * 2 * 8 = 80 bytes
    // blankRowBitmap: 2 * 8 = 16 bytes
    // Total: 116 bytes
    assertEquals(116, memSize);
  }

  @Test
  void memorySizeHandlesEmptyRowBitmaps() {
    int[] sortedValues = {};
    long[][] rowBitmaps = new long[0][];
    long[] blankRowBitmap = new long[1];

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    long memSize = index.memorySizeBytes();

    // Expected:
    // sortedValues: 0 * 4 = 0 bytes
    // rowBitmaps: 0 bytes (empty array)
    // blankRowBitmap: 1 * 8 = 8 bytes
    // Total: 8 bytes
    assertEquals(8, memSize);
  }

  // ============================================================
  // Helper Methods
  // ============================================================

  /**
   * Creates an array of empty bitmaps.
   *
   * @param count number of bitmaps
   * @param longsPerBitmap number of longs per bitmap
   * @return 2D array of bitmaps
   */
  private long[][] createRowBitmaps(int count, int longsPerBitmap) {
    return new long[count][longsPerBitmap];
  }
}
