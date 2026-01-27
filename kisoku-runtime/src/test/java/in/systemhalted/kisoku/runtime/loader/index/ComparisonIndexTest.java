package in.systemhalted.kisoku.runtime.loader.index;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.runtime.csv.Operator;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ComparisonIndex}. */
class ComparisonIndexTest {

  // ============================================================
  // Basic Operator Tests (Decision Table Semantics)
  // ============================================================

  @Test
  void gtReturnsRowsWhereInputExceedsThreshold() {
    // Test setup: 5 unique thresholds {10, 20, 30, 40, 50}, each in its own row (0-4)
    // Row 5 is blank (no condition)
    // For GT operator with input=30:
    //   Rule "AGE GT 10" matches when input > 10 → 30 > 10 ✓
    //   Rule "AGE GT 20" matches when input > 20 → 30 > 20 ✓
    //   Rule "AGE GT 30" matches when input > 30 → 30 > 30 ✗ (not strictly greater)
    //   Rule "AGE GT 40" matches when input > 40 → 30 > 40 ✗
    //   Rule "AGE GT 50" matches when input > 50 → 30 > 50 ✗
    // Expected: rows 0 (threshold=10), row 1 (threshold=20), and row 5 (blank)
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }
    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    // GT with input=30: should return rows with thresholds < 30 + blank
    long[] candidates = index.getCandidates(30);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // threshold 10: 30 > 10 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // threshold 20: 30 > 20 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 2)); // threshold 30: 30 > 30 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 3)); // threshold 40: 30 > 40 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 4)); // threshold 50: 30 > 50 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(3, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void gteReturnsRowsWhereInputMeetsOrExceedsThreshold() {
    // Thresholds: row 0=10, row 1=20, row 2=30, row 3=40, row 4=50
    // Row 5 is blank
    // For GTE with input=30: matches rules where input >= threshold
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GTE, blankRowBitmap);

    // GTE with input=30: should return rows with thresholds <= 30 + blank
    long[] candidates = index.getCandidates(30);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // threshold 10: 30 >= 10 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // threshold 20: 30 >= 20 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // threshold 30: 30 >= 30 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 3)); // threshold 40: 30 >= 40 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 4)); // threshold 50: 30 >= 50 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(4, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void ltReturnsRowsWhereInputIsBelowThreshold() {
    // For LT with input=30: matches rules where input < threshold
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);

    // LT with input=30: should return rows with thresholds > 30 + blank
    long[] candidates = index.getCandidates(30);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // threshold 10: 30 < 10 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // threshold 20: 30 < 20 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 2)); // threshold 30: 30 < 30 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // threshold 40: 30 < 40 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // threshold 50: 30 < 50 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(3, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void lteReturnsRowsWhereInputIsAtOrBelowThreshold() {
    // For LTE with input=30: matches rules where input <= threshold
    int[] sortedValues = {10, 20, 30, 40, 50};
    long[][] rowBitmaps = createRowBitmaps(5, 1);
    for (int i = 0; i < 5; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LTE, blankRowBitmap);

    // LTE with input=30: should return rows with thresholds >= 30 + blank
    long[] candidates = index.getCandidates(30);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // threshold 10: 30 <= 10 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // threshold 20: 30 <= 20 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // threshold 30: 30 <= 30 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // threshold 40: 30 <= 40 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // threshold 50: 30 <= 50 ✓
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
    CandidateBitmap.set(rowBitmaps[0], 0); // row 0 has threshold 100
    CandidateBitmap.set(rowBitmaps[1], 1); // row 1 has threshold 200
    CandidateBitmap.set(rowBitmaps[2], 2); // row 2 has threshold 300

    // Rows 3, 4, 5 are blank
    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 3);
    CandidateBitmap.set(blankRowBitmap, 4);
    CandidateBitmap.set(blankRowBitmap, 5);

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    // GT with input=150: matches rules where 150 > threshold
    // 150 > 100 ✓, 150 > 200 ✗, 150 > 300 ✗
    long[] candidates = index.getCandidates(150);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // threshold 100: 150 > 100 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // threshold 200: 150 > 200 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 2)); // threshold 300: 150 > 300 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // blank
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // blank
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(4, CandidateBitmap.cardinality(candidates));
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

    // GT with input=5: matches rules where 5 > threshold
    // 5 > 10 ✗, 5 > 20 ✗, 5 > 30 ✗ → only blanks match
    long[] candidates = index.getCandidates(5);

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
    // Thresholds: 10, 30, 50 (no 20, 40)
    int[] sortedValues = {10, 30, 50};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    CandidateBitmap.set(rowBitmaps[0], 0);
    CandidateBitmap.set(rowBitmaps[1], 1);
    CandidateBitmap.set(rowBitmaps[2], 2);

    long[] blankRowBitmap = new long[1];

    ComparisonIndex index =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);

    // GT with input=25: matches rules where 25 > threshold
    // 25 > 10 ✓, 25 > 30 ✗, 25 > 50 ✗
    long[] candidates = index.getCandidates(25);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // threshold 10: 25 > 10 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // threshold 30: 25 > 30 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 2)); // threshold 50: 25 > 50 ✗
    assertEquals(1, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void inputBelowAllValues() {
    // Thresholds: 100, 200, 300
    int[] sortedValues = {100, 200, 300};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    for (int i = 0; i < 3; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];

    // GT with input=50: matches rules where 50 > threshold → none match (all thresholds > 50)
    ComparisonIndex gtIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);
    long[] gtCandidates = gtIndex.getCandidates(50);
    assertTrue(CandidateBitmap.isEmpty(gtCandidates));

    // LT with input=50: matches rules where 50 < threshold → all match (all thresholds > 50)
    ComparisonIndex ltIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);
    long[] ltCandidates = ltIndex.getCandidates(50);
    assertEquals(3, CandidateBitmap.cardinality(ltCandidates));
  }

  @Test
  void inputAboveAllValues() {
    // Thresholds: 100, 200, 300
    int[] sortedValues = {100, 200, 300};
    long[][] rowBitmaps = createRowBitmaps(3, 1);
    for (int i = 0; i < 3; i++) {
      CandidateBitmap.set(rowBitmaps[i], i);
    }

    long[] blankRowBitmap = new long[1];

    // GT with input=500: matches rules where 500 > threshold → all match
    ComparisonIndex gtIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);
    long[] gtCandidates = gtIndex.getCandidates(500);
    assertEquals(3, CandidateBitmap.cardinality(gtCandidates));

    // LT with input=500: matches rules where 500 < threshold → none match
    ComparisonIndex ltIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);
    long[] ltCandidates = ltIndex.getCandidates(500);
    assertTrue(CandidateBitmap.isEmpty(ltCandidates));
  }

  @Test
  void singleValueIndex() {
    // Single threshold: 50
    int[] sortedValues = {50};
    long[][] rowBitmaps = createRowBitmaps(1, 1);
    CandidateBitmap.set(rowBitmaps[0], 0);

    long[] blankRowBitmap = new long[1];
    CandidateBitmap.set(blankRowBitmap, 1);

    // GT with input=50: 50 > 50 is false, only blank matches
    ComparisonIndex gtIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GT, blankRowBitmap);
    long[] gtCandidates = gtIndex.getCandidates(50);
    assertEquals(1, CandidateBitmap.cardinality(gtCandidates));
    assertTrue(CandidateBitmap.isSet(gtCandidates, 1)); // blank row

    // GTE with input=50: 50 >= 50 is true, threshold row + blank
    ComparisonIndex gteIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.GTE, blankRowBitmap);
    long[] gteCandidates = gteIndex.getCandidates(50);
    assertEquals(2, CandidateBitmap.cardinality(gteCandidates));
    assertTrue(CandidateBitmap.isSet(gteCandidates, 0)); // threshold row
    assertTrue(CandidateBitmap.isSet(gteCandidates, 1)); // blank row

    // LT with input=50: 50 < 50 is false, only blank matches
    ComparisonIndex ltIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LT, blankRowBitmap);
    long[] ltCandidates = ltIndex.getCandidates(50);
    assertEquals(1, CandidateBitmap.cardinality(ltCandidates));
    assertTrue(CandidateBitmap.isSet(ltCandidates, 1)); // blank row

    // LTE with input=50: 50 <= 50 is true, threshold row + blank
    ComparisonIndex lteIndex =
        new ComparisonIndex(sortedValues, rowBitmaps, Operator.LTE, blankRowBitmap);
    long[] lteCandidates = lteIndex.getCandidates(50);
    assertEquals(2, CandidateBitmap.cardinality(lteCandidates));
    assertTrue(CandidateBitmap.isSet(lteCandidates, 0)); // threshold row
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
  // Build Factory Method Tests
  // ============================================================

  @Test
  void buildCreatesIndexFromColumnData() {
    int rowCount = 6;
    // Row thresholds: 30, 10, 50, 20, 40, (blank)
    // After sorting: 10(row1), 20(row3), 30(row0), 40(row4), 50(row2)
    int[] values = {30, 10, 50, 20, 40, 0};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4); // Row 5 is blank

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.GT, rowCount);

    // GT with input=25: matches rules where 25 > threshold
    // 25 > 10 ✓ (row1), 25 > 20 ✓ (row3), 25 > 30 ✗, 25 > 40 ✗, 25 > 50 ✗
    long[] candidates = index.getCandidates(25);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // threshold 30: 25 > 30 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // threshold 10: 25 > 10 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 2)); // threshold 50: 25 > 50 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // threshold 20: 25 > 20 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 4)); // threshold 40: 25 > 40 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // blank
    assertEquals(3, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void buildHandlesDuplicateValues() {
    int rowCount = 8;
    // Rows 0,3,6 have threshold 100; rows 1,4 have threshold 200; rows 2,5 have threshold 50
    // Row 7 is blank
    int[] values = {100, 200, 50, 100, 200, 50, 100, 0};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4, 5, 6);

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.GTE, rowCount);

    // GTE with input=100: matches rules where 100 >= threshold
    // 100 >= 50 ✓ (rows 2,5), 100 >= 100 ✓ (rows 0,3,6), 100 >= 200 ✗
    long[] candidates = index.getCandidates(100);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // threshold 100: 100 >= 100 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // threshold 200: 100 >= 200 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // threshold 50: 100 >= 50 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 3)); // threshold 100: 100 >= 100 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 4)); // threshold 200: 100 >= 200 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 5)); // threshold 50: 100 >= 50 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 6)); // threshold 100: 100 >= 100 ✓
    assertTrue(CandidateBitmap.isSet(candidates, 7)); // blank
    assertEquals(6, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void buildWithLtOperator() {
    int rowCount = 5;
    // Thresholds: 50, 30, 70, 10, 90
    int[] values = {50, 30, 70, 10, 90};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4);

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.LT, rowCount);

    // LT with input=50: matches rules where 50 < threshold
    // 50 < 50 ✗, 50 < 30 ✗, 50 < 70 ✓ (row2), 50 < 10 ✗, 50 < 90 ✓ (row4)
    long[] candidates = index.getCandidates(50);

    assertFalse(CandidateBitmap.isSet(candidates, 0)); // threshold 50: 50 < 50 ✗
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // threshold 30: 50 < 30 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // threshold 70: 50 < 70 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 3)); // threshold 10: 50 < 10 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // threshold 90: 50 < 90 ✓
    assertEquals(2, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void buildWithLteOperator() {
    int rowCount = 5;
    // Thresholds: 50, 30, 70, 10, 90
    int[] values = {50, 30, 70, 10, 90};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3, 4);

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.LTE, rowCount);

    // LTE with input=50: matches rules where 50 <= threshold
    // 50 <= 50 ✓, 50 <= 30 ✗, 50 <= 70 ✓, 50 <= 10 ✗, 50 <= 90 ✓
    long[] candidates = index.getCandidates(50);

    assertTrue(CandidateBitmap.isSet(candidates, 0)); // threshold 50: 50 <= 50 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 1)); // threshold 30: 50 <= 30 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 2)); // threshold 70: 50 <= 70 ✓
    assertFalse(CandidateBitmap.isSet(candidates, 3)); // threshold 10: 50 <= 10 ✗
    assertTrue(CandidateBitmap.isSet(candidates, 4)); // threshold 90: 50 <= 90 ✓
    assertEquals(3, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void buildWithAllRowsBlank() {
    int rowCount = 5;
    int[] values = {0, 0, 0, 0, 0}; // Values don't matter when all blank
    byte[] presence = new byte[(rowCount + 7) / 8]; // All zeros = all blank

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.GT, rowCount);

    // Any search should return all rows (all blank = all match)
    long[] candidates = index.getCandidates(123);
    assertEquals(5, CandidateBitmap.cardinality(candidates));
  }

  @Test
  void buildWithNoBlankRows() {
    int rowCount = 4;
    // Thresholds: 10, 20, 30, 40
    int[] values = {10, 20, 30, 40};
    byte[] presence = createPresenceBitmap(rowCount, 0, 1, 2, 3); // All have conditions

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.GT, rowCount);

    // GT with input=25: matches rules where 25 > threshold
    // 25 > 10 ✓ (row0), 25 > 20 ✓ (row1), 25 > 30 ✗, 25 > 40 ✗
    long[] candidates = index.getCandidates(25);
    assertEquals(2, CandidateBitmap.cardinality(candidates));
    assertTrue(CandidateBitmap.isSet(candidates, 0)); // threshold 10
    assertTrue(CandidateBitmap.isSet(candidates, 1)); // threshold 20

    // GT with input=5: 5 > any threshold? No → empty
    long[] noneMatch = index.getCandidates(5);
    assertTrue(CandidateBitmap.isEmpty(noneMatch));
  }

  @Test
  void buildWithSingleRow() {
    int rowCount = 1;
    // Threshold: 42
    int[] values = {42};
    byte[] presence = createPresenceBitmap(rowCount, 0);

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.GTE, rowCount);

    // GTE with input=42: 42 >= 42 ✓
    long[] candidates42 = index.getCandidates(42);
    assertEquals(1, CandidateBitmap.cardinality(candidates42));
    assertTrue(CandidateBitmap.isSet(candidates42, 0));

    // GTE with input=41: 41 >= 42 ✗
    long[] candidates41 = index.getCandidates(41);
    assertTrue(CandidateBitmap.isEmpty(candidates41));
  }

  @Test
  void buildWithLargeRowCount() {
    int rowCount = 1000;
    int[] values = new int[rowCount];
    byte[] presence = new byte[(rowCount + 7) / 8];

    // Create 100 unique values (0-99), with some blanks
    for (int i = 0; i < rowCount; i++) {
      values[i] = i % 100;
      if (i % 10 != 0) { // Every 10th row is blank
        int byteIndex = i / 8;
        int bitIndex = 7 - (i % 8);
        presence[byteIndex] |= (1 << bitIndex);
      }
    }

    ComparisonIndex index = ComparisonIndex.build(values, presence, Operator.GT, rowCount);

    // GT 50: should match values 51-99 plus blank rows
    long[] candidates = index.getCandidates(50);

    // 49 values (51-99) * 9 rows each = 441 value matches
    // Plus 100 blank rows (every 10th row)
    // Total should be 541
    int cardinality = CandidateBitmap.cardinality(candidates);
    assertTrue(cardinality > 400, "Expected many matches, got: " + cardinality);
    assertTrue(cardinality < 600, "Expected reasonable matches, got: " + cardinality);
  }

  // ============================================================
  // Helper Methods
  // ============================================================

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
