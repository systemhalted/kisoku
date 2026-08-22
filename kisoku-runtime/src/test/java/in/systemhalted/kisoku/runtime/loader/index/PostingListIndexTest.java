package in.systemhalted.kisoku.runtime.loader.index;

import static org.junit.jupiter.api.Assertions.*;

import in.systemhalted.kisoku.runtime.csv.Operator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PostingListIndex} semantics per operator: exact candidate counts,
 * enumeration slices, blank handling, absent inputs, and codes the index has never seen.
 *
 * <p>Codes here are raw longs; the index is agnostic to how they were produced.
 */
class PostingListIndexTest {

  /** Builds an index over scalar column data; a value of null means a blank cell. */
  private static PostingListIndex build(Operator op, Long... cells) {
    int conditionRows = 0;
    for (Long cell : cells) {
      if (cell != null) {
        conditionRows++;
      }
    }
    PostingListIndexBuilder builder = new PostingListIndexBuilder(op, conditionRows, cells.length);
    for (int row = 0; row < cells.length; row++) {
      if (cells[row] != null) {
        builder.addPair(cells[row], row);
      } else {
        builder.addBlank(row);
      }
    }
    return builder.build();
  }

  private static List<Integer> matchRows(PostingListIndex index, long code) {
    List<Integer> rows = new ArrayList<>();
    for (int i = index.matchStart(code); i < index.matchEnd(code); i++) {
      rows.add(index.rowAt(i));
    }
    return rows;
  }

  private static List<Integer> blankRows(PostingListIndex index) {
    List<Integer> rows = new ArrayList<>();
    for (int i = 0; i < index.blankCount(); i++) {
      rows.add(index.blankRowAt(i));
    }
    return rows;
  }

  // ------------------------------------------------------------------- EQ

  @Test
  void eqCountsAndEnumeratesMatchingRowsPlusBlanks() {
    // rows:            0     1     2     3    4      5
    PostingListIndex index = build(Operator.EQ, 100L, 200L, 100L, null, 300L, 100L);

    assertEquals(4, index.candidateCount(100, true), "three matches + one blank");
    assertEquals(List.of(0, 2, 5), matchRows(index, 100), "ascending row order");
    assertEquals(List.of(3), blankRows(index));
    assertTrue(index.enumerationRowOrdered());

    assertEquals(2, index.candidateCount(200, true));
    assertEquals(List.of(1), matchRows(index, 200));
  }

  @Test
  void eqUnknownCodeLeavesOnlyBlanks() {
    PostingListIndex index = build(Operator.EQ, 100L, 200L, null);

    assertEquals(1, index.candidateCount(999, true), "only the blank row");
    assertEquals(List.of(), matchRows(index, 999), "empty match slice");
  }

  @Test
  void eqZeroCandidatesMeansNoRuleCanMatch() {
    PostingListIndex index = build(Operator.EQ, 100L, 200L); // no blanks

    assertEquals(0, index.candidateCount(999, true));
  }

  @Test
  void absentInputCountsOnlyBlanks() {
    PostingListIndex index = build(Operator.EQ, 100L, null, 200L, null);

    assertEquals(2, index.candidateCount(0, false));
    assertTrue(index.enumerable(false));
    assertEquals(List.of(1, 3), blankRows(index));
  }

  // ------------------------------------------------------------ comparisons

  /** Thresholds 10,20,30 at rows 0,1,2 plus a blank at row 3. */
  private static PostingListIndex comparisonIndex(Operator op) {
    return build(op, 10L, 20L, 30L, null);
  }

  @Test
  void gtMatchesThresholdsBelowInput() {
    PostingListIndex index = comparisonIndex(Operator.GT);

    // Rule "X GT T" matches input V when V > T -> thresholds strictly below V.
    assertEquals(2 + 1, index.candidateCount(25, true), "10, 20 + blank");
    assertEquals(List.of(0, 1), matchRows(index, 25));
    assertEquals(0 + 1, index.candidateCount(10, true), "10 itself is not < 10");
    assertEquals(3 + 1, index.candidateCount(1000, true));
    assertFalse(index.enumerationRowOrdered());
  }

  @Test
  void gteMatchesThresholdsAtOrBelowInput() {
    PostingListIndex index = comparisonIndex(Operator.GTE);

    assertEquals(2 + 1, index.candidateCount(20, true), "10, 20 + blank");
    assertEquals(List.of(0, 1), matchRows(index, 20));
    assertEquals(0 + 1, index.candidateCount(5, true));
  }

  @Test
  void ltMatchesThresholdsAboveInput() {
    PostingListIndex index = comparisonIndex(Operator.LT);

    assertEquals(2 + 1, index.candidateCount(15, true), "20, 30 + blank");
    assertEquals(List.of(1, 2), matchRows(index, 15));
    assertEquals(0 + 1, index.candidateCount(30, true), "30 itself is not > 30");
  }

  @Test
  void lteMatchesThresholdsAtOrAboveInput() {
    PostingListIndex index = comparisonIndex(Operator.LTE);

    assertEquals(2 + 1, index.candidateCount(20, true), "20, 30 + blank");
    assertEquals(List.of(1, 2), matchRows(index, 20));
    assertEquals(3 + 1, index.candidateCount(-5, true));
  }

  @Test
  void comparisonSliceSpansCodesInCodeOrderNotRowOrder() {
    // Same threshold pattern but rows arrive in an order where code order != row order.
    // rows:                 0    1    2
    PostingListIndex index = build(Operator.GT, 30L, 10L, 20L);

    // GT 25 -> thresholds 10 (row 1) and 20 (row 2); slice is code-ordered.
    assertEquals(List.of(1, 2), matchRows(index, 25));
  }

  // ----------------------------------------------------------- negative ops

  @Test
  void neCountsComplementAndIsNotEnumerable() {
    PostingListIndex index = build(Operator.NE, 100L, 200L, 100L, null);

    // NE 100: condition rows not holding 100 -> row 1, plus the blank.
    assertEquals(1 + 1, index.candidateCount(100, true));
    // NE of an unseen value: all three condition rows + blank.
    assertEquals(3 + 1, index.candidateCount(999, true));
    assertFalse(index.enumerable(true));
    assertTrue(index.enumerable(false));
  }

  @Test
  void neZeroCandidatesWhenEveryRowHoldsTheValue() {
    PostingListIndex index = build(Operator.NE, 100L, 100L); // no blanks

    assertEquals(0, index.candidateCount(100, true), "every rule excludes this input");
  }

  // ------------------------------------------------------------------ sets

  /** Rows 0..2: {10,20,30}, {20,40}, blank. Built as IN postings, one pair per member. */
  private static PostingListIndex setIndex(Operator op) {
    PostingListIndexBuilder builder = new PostingListIndexBuilder(op, 2, 5);
    builder.addPair(10, 0);
    builder.addPair(20, 0);
    builder.addPair(30, 0);
    builder.addPair(20, 1);
    builder.addPair(40, 1);
    builder.addBlank(2);
    return builder.build();
  }

  @Test
  void inCountsRowsContainingTheValue() {
    PostingListIndex index = setIndex(Operator.IN);

    assertEquals(2 + 1, index.candidateCount(20, true), "rows 0 and 1 + blank");
    assertEquals(List.of(0, 1), matchRows(index, 20));
    assertEquals(1 + 1, index.candidateCount(40, true));
    assertEquals(0 + 1, index.candidateCount(999, true), "unseen member: blank only");
    assertTrue(index.enumerationRowOrdered());
  }

  @Test
  void notInCountsRowsNotContainingTheValue() {
    PostingListIndex index = setIndex(Operator.NOT_IN);

    assertEquals(0 + 1, index.candidateCount(20, true), "both rows contain 20; blank only");
    assertEquals(1 + 1, index.candidateCount(10, true), "row 1 does not contain 10");
    assertEquals(2 + 1, index.candidateCount(999, true), "no row contains it");
    assertFalse(index.enumerable(true));
  }

  @Test
  void duplicateSetMembersAreDeduplicated() {
    // A cell like "(A,A)" adds the same (code, row) pair twice; the complement count must not
    // double-count that row, and the postings must store it once.
    PostingListIndexBuilder notIn = new PostingListIndexBuilder(Operator.NOT_IN, 1, 2);
    notIn.addPair(10, 0);
    notIn.addPair(10, 0);
    assertEquals(0, notIn.build().candidateCount(10, true), "1 condition row - 1 matching row");

    PostingListIndexBuilder in = new PostingListIndexBuilder(Operator.IN, 1, 2);
    in.addPair(10, 0);
    in.addPair(10, 0);
    PostingListIndex index = in.build();
    assertEquals(List.of(0), matchRows(index, 10), "row stored once");
    assertEquals(1, index.candidateCount(10, true));
  }

  // ------------------------------------------------------------------ misc

  @Test
  void allBlankColumnHasNoPostings() {
    PostingListIndex index = build(Operator.EQ, null, null, null);

    assertEquals(0, index.distinctCodeCount());
    assertEquals(3, index.candidateCount(42, true), "every row is a blank candidate");
    assertEquals(List.of(0, 1, 2), blankRows(index));
  }

  @Test
  void unsortedInsertionOrderIsSortedStably() {
    // rows:                 0     1     2     3     4
    PostingListIndex index = build(Operator.EQ, 300L, 100L, 300L, 100L, 200L);

    assertEquals(List.of(1, 3), matchRows(index, 100), "rows ascending within a code");
    assertEquals(List.of(4), matchRows(index, 200));
    assertEquals(List.of(0, 2), matchRows(index, 300));
    assertEquals(3, index.distinctCodeCount());
  }

  @Test
  void memorySizeIsLinearInColumnData() {
    PostingListIndex index = build(Operator.EQ, 100L, 200L, 100L, null);

    // codes: 2*8, offsets: 3*4, postings: 3*4, blanks: 1*4
    assertEquals(2 * 8 + 3 * 4 + 3 * 4 + 1 * 4, index.memorySizeBytes());
  }

  @Test
  void extremeCodesAreHandled() {
    PostingListIndex index = build(Operator.LTE, Long.MIN_VALUE, 0L, Long.MAX_VALUE);

    assertEquals(3, index.candidateCount(Long.MIN_VALUE, true), "all thresholds >= MIN_VALUE");
    assertEquals(1, index.candidateCount(Long.MAX_VALUE, true));
    assertEquals(0, index.candidateCount(Long.MAX_VALUE, true) - 1 + 0, "sanity");
  }
}
