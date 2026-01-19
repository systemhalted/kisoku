package in.systemhalted.kisoku.runtime.loader.index;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link CandidateBitmap} utility class. */
class CandidateBitmapTest {

  @Test
  void longCountCalculatesCorrectlyForVariousSizes() {
    assertEquals(0, CandidateBitmap.longCount(0));
    assertEquals(1, CandidateBitmap.longCount(1));
    assertEquals(1, CandidateBitmap.longCount(64));
    assertEquals(2, CandidateBitmap.longCount(65));
    assertEquals(2, CandidateBitmap.longCount(128));
    assertEquals(78125, CandidateBitmap.longCount(5_000_000)); // 5M rows
  }

  @Test
  void allOnesCreatesFullySetBitmap() {
    long[] bitmap = CandidateBitmap.allOnes(100);

    // First 64 bits should all be set
    assertEquals(~0L, bitmap[0]);

    // Last word should have only 36 bits set (100 - 64 = 36)
    assertEquals((1L << 36) - 1, bitmap[1]);

    // Check individual bits
    for (int i = 0; i < 100; i++) {
      assertTrue(CandidateBitmap.isSet(bitmap, i), "Bit " + i + " should be set");
    }
    assertFalse(CandidateBitmap.isSet(bitmap, 100), "Bit 100 should not be set");
  }

  @Test
  void allOnesHandlesExact64Multiple() {
    long[] bitmap = CandidateBitmap.allOnes(128);
    assertEquals(2, bitmap.length);
    assertEquals(~0L, bitmap[0]);
    assertEquals(~0L, bitmap[1]);
  }

  @Test
  void emptyCreatesZeroBitmap() {
    long[] bitmap = CandidateBitmap.empty(100);
    assertEquals(2, bitmap.length);
    assertEquals(0L, bitmap[0]);
    assertEquals(0L, bitmap[1]);
    assertTrue(CandidateBitmap.isEmpty(bitmap));
  }

  @Test
  void andIntersectsBitmaps() {
    // Create bitmap with bits 0-63 set
    long[] a = new long[] {~0L, 0L};
    // Create bitmap with bits 32-95 set
    long[] b = new long[] {~0L << 32, ~0L >>> 32};

    long[] result = CandidateBitmap.and(a, b);

    // Only bits 32-63 should be set (intersection)
    assertEquals(~0L << 32, result[0]);
    assertEquals(0L, result[1]);
  }

  @Test
  void andInPlaceModifiesFirstBitmap() {
    long[] a = new long[] {~0L, ~0L};
    long[] b = new long[] {0xF0F0F0F0F0F0F0F0L, 0L};

    CandidateBitmap.andInPlace(a, b);

    assertEquals(0xF0F0F0F0F0F0F0F0L, a[0]);
    assertEquals(0L, a[1]); // Cleared because b[1] is 0
  }

  @Test
  void orUnionsBitmaps() {
    long[] a = new long[] {0x00FF00FF00FF00FFL, 0L};
    long[] b = new long[] {0xFF00FF00FF00FF00L, 0xFFL};

    long[] result = CandidateBitmap.or(a, b);

    assertEquals(~0L, result[0]);
    assertEquals(0xFFL, result[1]);
  }

  @Test
  void andNotExcludesBits() {
    long[] a = CandidateBitmap.allOnes(100);
    long[] b = new long[] {~0L, 0L}; // First 64 bits set

    long[] result = CandidateBitmap.andNot(a, b);

    assertEquals(0L, result[0]); // All excluded
    assertEquals((1L << 36) - 1, result[1]); // Bits 64-99 remain
  }

  @Test
  void findFirstReturnsLowestSetBit() {
    long[] bitmap = CandidateBitmap.empty(100);
    CandidateBitmap.set(bitmap, 42);
    CandidateBitmap.set(bitmap, 77);

    assertEquals(42, CandidateBitmap.findFirst(bitmap));
  }

  @Test
  void findFirstReturnsMinusOneForEmptyBitmap() {
    long[] bitmap = CandidateBitmap.empty(100);
    assertEquals(-1, CandidateBitmap.findFirst(bitmap));
  }

  @Test
  void isEmptyDetectsEmptyAndNonEmptyBitmaps() {
    long[] empty = CandidateBitmap.empty(100);
    assertTrue(CandidateBitmap.isEmpty(empty));

    CandidateBitmap.set(empty, 50);
    assertFalse(CandidateBitmap.isEmpty(empty));
  }

  @Test
  void setAndIsSetWorkCorrectly() {
    long[] bitmap = CandidateBitmap.empty(200);

    // Set specific bits
    int[] bitsToSet = {0, 1, 63, 64, 65, 127, 128, 199};
    for (int bit : bitsToSet) {
      assertFalse(
          CandidateBitmap.isSet(bitmap, bit), "Bit " + bit + " should not be set initially");
      CandidateBitmap.set(bitmap, bit);
      assertTrue(CandidateBitmap.isSet(bitmap, bit), "Bit " + bit + " should be set after set()");
    }

    // Verify other bits are not set
    assertFalse(CandidateBitmap.isSet(bitmap, 2));
    assertFalse(CandidateBitmap.isSet(bitmap, 62));
    assertFalse(CandidateBitmap.isSet(bitmap, 100));
  }

  @Test
  void cardinalityCountsSetBits() {
    long[] bitmap = CandidateBitmap.empty(100);
    assertEquals(0, CandidateBitmap.cardinality(bitmap));

    CandidateBitmap.set(bitmap, 10);
    CandidateBitmap.set(bitmap, 20);
    CandidateBitmap.set(bitmap, 70);
    assertEquals(3, CandidateBitmap.cardinality(bitmap));

    long[] allOnes = CandidateBitmap.allOnes(100);
    assertEquals(100, CandidateBitmap.cardinality(allOnes));
  }

  @Test
  void copyCreatesIndependentCopy() {
    long[] original = CandidateBitmap.allOnes(100);
    long[] copy = CandidateBitmap.copy(original);

    // Modify original
    original[0] = 0L;

    // Copy should be unchanged
    assertEquals(~0L, copy[0]);
  }

  @Test
  void handlesLargeRowCount() {
    int rowCount = 5_000_000; // 5M rows
    long[] bitmap = CandidateBitmap.allOnes(rowCount);

    assertEquals(78125, bitmap.length);
    assertEquals(rowCount, CandidateBitmap.cardinality(bitmap));
    assertTrue(CandidateBitmap.isSet(bitmap, 0));
    assertTrue(CandidateBitmap.isSet(bitmap, rowCount - 1));
    assertFalse(CandidateBitmap.isSet(bitmap, rowCount));
  }
}
