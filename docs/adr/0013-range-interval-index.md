# ADR-0013: Interval Index for Range Operators

## Status

Accepted

## Context

`BETWEEN_*`/`NOT_BETWEEN_*` were the last unindexed operators (ADR-0011 covered the rest).
A table keyed on ranges — the documentation's own headline example — got no candidate
narrowing at all: every evaluation fell back to verifying all rows.

A `BETWEEN` match set is an interval-stabbing query, which is not contiguous in any single
sorted order, so the posting-list layout could not represent it directly.

## Decision

Each range column gets a **`RangeIntervalIndex`**: the same CSR posting structure built
twice, once sorted by each row's *min* code and once by its *max* code, in one direct
buffer.

- **Exact counts by inclusion-exclusion.** For input `v`, rows matching
  `BETWEEN_INCLUSIVE` are exactly `#(min ≤ v) − #(max < v)` — two binary searches. The
  subtraction is sound because every included row has `min ≤ max`; rows with inverted
  bounds (which can never match) are excluded from both structures at build time, while
  still counted in the condition total so the `NOT_BETWEEN_*` complement counts them as
  always matching.
- **Enumeration by the cheaper bound.** Each bound gives a contiguous *superset* of the
  matches: a prefix of the min-sorted postings (`min ≤ v`) or a suffix of the max-sorted
  postings (`max ≥ v`). The index exposes whichever side is smaller through a virtual row
  space; the evaluator's exhaustive verification filters the superset, so correctness is
  unaffected by the over-approximation.
- The evaluator's driver selection now distinguishes `candidateCount` (exact, for the
  zero-candidates early exit) from `enumerationCost` (what a driver enumeration would
  visit), introduced on a new `ColumnIndex` interface implemented by both index kinds.
  `NOT_BETWEEN_*` are count-only, like `NE`/`NOT_IN`.

## Consequences

- Every operator the engine supports is now index-covered: enumeration for the positive
  operators, exact candidate counts for the negative ones.
- Size stays linear in the column's data (each included row appears twice, plus blanks).
- A range driver's enumeration may visit rows that don't match; the cost model accounts
  for that, and verification keeps results exact.
