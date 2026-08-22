package in.systemhalted.kisoku.runtime.loader.index;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@link RangeIntervalIndex} semantics: exact inclusion-exclusion counts per operator, inverted
 * ranges, blank handling, and enumeration as a superset of the true match set.
 */
class RangeIntervalIndexTest {

  /** Builds an index over rows given as {min,max} pairs; null means a blank cell. */
  private static RangeIntervalIndex build(Operator op, long[][] rows) {
    int n = rows.length;
    long[] mins = new long[n];
    long[] maxs = new long[n];
    byte[] presence = new byte[(n + 7) / 8];
    for (int i = 0; i < n; i++) {
      if (rows[i] != null) {
        presence[i / 8] |= (byte) (1 << (7 - (i % 8)));
        mins[i] = rows[i][0];
        maxs[i] = rows[i][1];
      }
    }
    return RangeIntervalIndex.build(mins, maxs, presence, op, n);
  }

  /** Rows visited by the driver enumeration for a supplied value. */
  private static List<Integer> enumerated(RangeIntervalIndex index, long code) {
    List<Integer> rows = new ArrayList<>();
    for (int i = index.matchStart(code); i < index.matchEnd(code); i++) {
      rows.add(index.rowAt(i));
    }
    for (int i = 0; i < index.blankCount(); i++) {
      rows.add(index.blankRowAt(i));
    }
    return rows;
  }

  /** Ground truth: rows whose interval admits the value under the operator. */
  private static TreeSet<Integer> expectedMatches(Operator op, long[][] rows, long v) {
    TreeSet<Integer> matches = new TreeSet<>();
    for (int i = 0; i < rows.length; i++) {
      if (rows[i] == null) {
        matches.add(i); // blanks always match
        continue;
      }
      long min = rows[i][0];
      long max = rows[i][1];
      boolean between =
          switch (op) {
            case BETWEEN_INCLUSIVE, NOT_BETWEEN_INCLUSIVE -> v >= min && v <= max;
            case BETWEEN_EXCLUSIVE, NOT_BETWEEN_EXCLUSIVE -> v > min && v < max;
            default -> throw new IllegalStateException();
          };
      boolean negated =
          op == Operator.NOT_BETWEEN_INCLUSIVE || op == Operator.NOT_BETWEEN_EXCLUSIVE;
      if (between != negated) {
        matches.add(i);
      }
    }
    return matches;
  }

  private static final long[][] TABLE = {
    {10, 20}, // 0
    {15, 30}, // 1
    {5, 12}, // 2
    null, // 3: blank
    {25, 25}, // 4: single point
    {40, 4}, // 5: inverted - can never BETWEEN-match, always NOT_BETWEEN-matches
  };

  @Test
  void countsAreExactForEveryOperatorAndProbe() {
    for (Operator op :
        new Operator[] {
          Operator.BETWEEN_INCLUSIVE,
          Operator.BETWEEN_EXCLUSIVE,
          Operator.NOT_BETWEEN_INCLUSIVE,
          Operator.NOT_BETWEEN_EXCLUSIVE
        }) {
      RangeIntervalIndex index = build(op, TABLE);
      for (long v : new long[] {0, 4, 5, 10, 12, 15, 20, 25, 26, 30, 31, 40, 100}) {
        assertEquals(
            expectedMatches(op, TABLE, v).size(),
            index.candidateCount(v, true),
            op + " count at v=" + v);
      }
    }
  }

  @Test
  void enumerationIsASupersetOfTheMatchesForBetween() {
    for (Operator op : new Operator[] {Operator.BETWEEN_INCLUSIVE, Operator.BETWEEN_EXCLUSIVE}) {
      RangeIntervalIndex index = build(op, TABLE);
      assertTrue(index.enumerable(true), op + " should enumerate");
      for (long v : new long[] {0, 5, 12, 15, 20, 25, 30, 100}) {
        List<Integer> visited = enumerated(index, v);
        for (int match : expectedMatches(op, TABLE, v)) {
          assertTrue(
              visited.contains(match),
              op + " at v=" + v + ": match row " + match + " missing from " + visited);
        }
        assertTrue(
            visited.size() >= index.candidateCount(v, true),
            op + " enumeration cost covers the count");
      }
    }
  }

  @Test
  void enumerationPicksTheCheaperBound() {
    RangeIntervalIndex index = build(Operator.BETWEEN_INCLUSIVE, TABLE);
    // v=5: mins<=5 -> only row 2 (min side size 1); maxes>=5 -> rows 0,1,2,4 (size 4).
    assertEquals(1 + 1, index.enumerationCost(5, true), "min side (1) + blank (1)");
    // v=30: mins<=30 -> rows 0,1,2,4 (4); maxes>=30 -> row 1 (1).
    assertEquals(1 + 1, index.enumerationCost(30, true), "max side (1) + blank (1)");
  }

  @Test
  void notBetweenIsCountOnly() {
    RangeIntervalIndex index = build(Operator.NOT_BETWEEN_INCLUSIVE, TABLE);
    assertFalse(index.enumerable(true));
    assertTrue(index.enumerable(false), "absent input enumerates blanks only");
    // v=15 is inside rows 0,1 -> NOT_BETWEEN matches rows 2,4 + inverted row 5 + blank row 3.
    assertEquals(4, index.candidateCount(15, true));
  }

  @Test
  void invertedRangeNeverMatchesBetweenAndZeroExitStaysSound() {
    long[][] onlyInverted = {{40, 4}};
    RangeIntervalIndex between = build(Operator.BETWEEN_INCLUSIVE, onlyInverted);
    // No value can satisfy 40 <= v <= 4; count must be exactly 0, never negative.
    for (long v : new long[] {0, 4, 20, 40, 100}) {
      assertEquals(0, between.candidateCount(v, true), "v=" + v);
    }

    RangeIntervalIndex notBetween = build(Operator.NOT_BETWEEN_INCLUSIVE, onlyInverted);
    for (long v : new long[] {0, 4, 20, 40, 100}) {
      assertEquals(
          1, notBetween.candidateCount(v, true), "inverted row always NOT_BETWEEN-matches");
    }
  }

  @Test
  void singlePointIntervalRespectsBoundaryStrictness() {
    long[][] point = {{25, 25}};
    assertEquals(1, build(Operator.BETWEEN_INCLUSIVE, point).candidateCount(25, true));
    assertEquals(0, build(Operator.BETWEEN_EXCLUSIVE, point).candidateCount(25, true));
    assertEquals(0, build(Operator.BETWEEN_INCLUSIVE, point).candidateCount(24, true));
  }

  @Test
  void absentInputCountsOnlyBlanks() {
    RangeIntervalIndex index = build(Operator.BETWEEN_INCLUSIVE, TABLE);
    assertEquals(1, index.candidateCount(0, false));
    assertEquals(1, index.enumerationCost(0, false));
    assertEquals(3, index.blankRowAt(0));
  }

  @Test
  void allBlankColumnMatchesEverything() {
    RangeIntervalIndex index = build(Operator.BETWEEN_INCLUSIVE, new long[][] {null, null});
    assertEquals(2, index.candidateCount(42, true));
    assertEquals(2, index.blankCount());
  }
}
