# ADR-0014: Persisted Index Blocks (Format 4.1)

## Status

Accepted

## Context

Indexes were built at load time from decoder-materialized column data: correct, but the
build (sorting every indexable column) dominated load — 12–13 s of the ~13 s load at 5M
rows — and was repeated on every load of the same artifact.

## Decision

The compiler builds the per-column indexes once and **persists them into the artifact**
(format 4.1, on by default, `CompileOptions.withPersistIndexes(false)` to opt out):

- The header's reserved bytes 52–59 become `index_offset` (zero = no persisted indexes),
  pointing at a per-column directory of `(block offset, block length)` pairs — written as
  a placeholder during the sequential stitch and patched afterward, since block sizes are
  only known once built.
- Each block is the index's small integer header plus its array region **byte-for-byte
  identical to the in-memory layout**, so the loader constructs `PostingListIndex` /
  `RangeIntervalIndex` directly over a mapped slice — no parsing, no copying, no build.
  The block kind is implied by the column's operator.
- The compiler builds each index from the same per-column temporary it stitches the data
  section from, feeding rows in evaluation order, one column at a time — compile heap
  stays bounded by the largest single column.
- Minor version bump, compatible both ways within major 4: a 4.0 reader ignores the
  trailing index section, and a 4.1 reader treats a zero `index_offset` as "build at
  load", which also remains the fallback for artifacts compiled with persistence off.
  `LoadOptions.withPrewarmIndexes(false)` still disables indexes entirely.

## Consequences

Measured at 200K rows × 10 EQ columns: load(mmap) **245 ms → 1 ms**; compile 0.96 s →
1.5 s; artifact 20.6 MB → 28.3 MB. At 5M rows: load **7.6 s → 1 ms**, compile 22 s → 40 s
(inside the 60 s target), artifact 522 MB → 713 MB. The trade — index build cost paid once
at compile, artifact ~1.4× larger — is the right default for the compile-once/load-many
lifecycle the PRD describes. Parity between persisted, load-built, and unindexed evaluation is pinned by
`PersistedIndexTest` across both read paths.
