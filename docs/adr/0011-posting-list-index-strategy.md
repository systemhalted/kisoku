# ADR-0011: Off-Heap Posting-List Indexes Replace Dense Per-Value Bitmaps

## Status

Accepted

## Context

The original indexing strategy (ADR-0005, ADR-0009) kept one dense `long[ceil(rows/64)]`
bitmap per distinct value per indexed column, on the Java heap, and answered a lookup by
intersecting one bitmap per column into a freshly allocated candidate bitmap.

Measured against the PRD, that design failed three constraints at once:

- **Heap.** Cost per column is `distinct × rows / 8` bytes regardless of sparsity. At the
  PRD-typical 5M rows × 60 indexed columns × ~500 distinct values, that projects to ~18 GB
  against a 1 GB heap budget. Measured: 120 MB of index heap at 200K rows × 10 columns,
  doubling with row count.
- **Per-evaluation allocation.** Every evaluation cloned the all-rows bitmap and every
  `getCandidates` call allocated another. Measured 74 KB/eval at 200K rows, linear in rows
  (~1.9 MB/eval projected at 5M) — against the PRD's bounded-working-set requirement.
- **Candidate iteration.** After intersection, the winner was found by scanning all rows and
  testing membership, so latency tracked table size (45 µs at 200K → 160 µs at 1M rows).
  `ComparisonIndex` additionally OR-ed one full bitmap per distinct threshold per lookup.

## Decision

Replace the dense bitmaps with one **posting-list (CSR) index per column, in a direct
buffer off the heap**:

```
codes[distinct]      sorted distinct comparable codes   (8 B each)
offsets[distinct+1]  postings slice bounds per code     (4 B each)
postings[present]    row ids, ascending within a code   (4 B each)
blanks[blankCount]   blank-cell rows, ascending         (4 B each)
```

Each present cell is stored exactly once, so the index is linear in the column's data and
independent of value skew: ~20 MB per 5M-row column versus ~312 MB dense.

Evaluation changes from *intersect-then-scan* to *count, pick a driver, verify*:

1. Every indexed column reports an **exact candidate count** for the input in O(log distinct)
   — binary search plus an offset subtraction. A count of zero ends the evaluation.
2. The most selective **enumerable** column becomes the driver. Its candidate rows (postings
   slice plus blanks) are iterated directly; each is verified against **all** input columns
   through the decoders' pre-coerced match path.
3. Equality-shaped drivers (`EQ`, `IN`) enumerate in ascending row order, so the first full
   match is the winner. Comparison drivers span several codes, so the evaluator tracks the
   minimum matching row, and falls back to an in-order linear scan when the slice exceeds a
   quarter of the table.

Verification is exhaustive, so the driver choice is a pure optimization — any driver yields
the same winner. Single evaluation and the bulk kernel share this matcher
(`IndexedMatcher`), making bulk/single parity structural rather than tested-for.

Negative operators (`NE`, `NOT_IN`) index for **counts only** (condition rows minus the
code's postings): their match sets are complements and never worth enumerating, but a zero
count is still a correct early exit. This also brings `NE` under the index umbrella for the
first time.

The enumerated fast paths return the smallest matching physical row, which equals "first in
evaluation order" because the compiler writes rows in evaluation order (the rule-order
section is the identity permutation). The matcher verifies this at construction and falls
back to order-faithful linear scans otherwise.

## Consequences

Measured at 200K rows × 10 EQ columns × 500 distinct values (and 400K to confirm scaling):

| Metric | Dense bitmaps | Posting lists |
|---|---|---|
| Index heap | 120 MB (linear in rows) | ~0 (off-heap; buffer is linear in data) |
| Per-eval allocation | 74 KB, linear in rows | 1.0 KB, constant |
| Matching eval median | 45 µs @200K, 160 µs @1M | 12 µs, flat across sizes |
| Bulk 1000 variants | 66–75 ms | 11–20 ms |

Trade-offs accepted:

- A multi-column conjunction is resolved by verification from one driver rather than by
  bitmap AND across all columns. When two mid-selectivity columns would jointly be far more
  selective than either alone, this does more verification work — bounded by the driver's
  candidate count, with early exit per row on the first failing column.
- Index build sorts each column's (code, row) pairs at load time; transient heap during the
  sort is one column's data at a time.
- Indexes are still built at load time, not persisted in the artifact. Compile-time
  persistence (index sections read through the mapping) remains open as the follow-up that
  would also cut load time.

ADR-0005 and ADR-0009 are superseded by this ADR.
