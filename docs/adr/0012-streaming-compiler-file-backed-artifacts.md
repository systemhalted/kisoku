# ADR-0012: Streaming Compiler and File-Backed Artifacts (Format 4.0)

## Status

Accepted

## Context

The compiler materialized the entire CSV as `List<String[]>`, held several derived copies
(projected rows, per-column `ByteArrayOutputStream`s, the concatenated rule-data section),
and produced the artifact as one `byte[]`. Consequences, measured against the PRD:

- Compile heap was 3-4x the table as Java strings. The PRD maximum (20M rows x 170
  columns) is tens of gigabytes of strings - not compilable at all.
- Every offset in the format was a 32-bit int and the artifact had to exist as a single
  contiguous array, capping artifacts at 2 GB. The PRD maximum shape needs well over that.
- The 5M-row typical compile took 58-85 s against a 60 s target, much of it allocation churn.

## Decision

**Compile streams end to end; the artifact is a file with 64-bit offsets (format 4.0).**

The source is read twice and never held:

1. **Pass 1** streams the CSV collecting exactly what encoding needs up front: the string
   dictionary (bounded by distinct values, not rows), per-column decimal scales, one
   priority int per row, and the row count. Evaluation order is a stable ascending argsort
   of the priorities.
2. **Pass 2** streams the CSV again, encoding each cell to its order-preserving code as it
   is read and appending a small self-describing record to that column's temporary file, in
   source row order.
3. **Stitch** writes the artifact file sequentially - header, dictionary, column
   definitions, then each column's data. One column at a time: load its temporary file,
   index its records, iterate rows in evaluation order emitting the column's subsections
   (presence bitmap, then the per-operator layout), delete the temporary. Every section
   offset is known before the first byte is written, so nothing is patched.

Peak compile heap is the dictionary, one int per row, and the largest single column's
encoded data - independent of the table's total size. `DecisionTableSource.openStream()`
must be re-openable (file-backed sources are).

Format 4.0 changes:

- Header grows to 64 bytes; `dictionary_offset`, `columns_offset`, `data_offset`, and a new
  explicit `rule_order_offset` are all 64-bit. Column definitions grow to 24 bytes with a
  64-bit `data_offset`.
- The rule-order section is a 1-byte order type; type 0 (identity - what the compiler
  always writes, since rows are physically pre-sorted) carries no array at all.
- `CompiledRuleset` is file-backed: the compiler streams to a temporary file, `writeTo` is
  a copy, and `bytes()` materializes only artifacts small enough for one array.

The loader maps the artifact **per section**: the metadata region once, then each column's
data slice as its own `MappedByteBuffer` (sized by the next column's offset), so no mapping
spans 2 GB and the artifact size is unbounded while decoders keep their int-indexed
`ByteBuffer` reads. Loading a compiled ruleset maps its backing file directly - the old
copy-into-`allocateDirect` path is gone. Each individual column's data must stay under
2 GB, which the compiler enforces with a clear error.

## Consequences

Measured at 5M rows x 10 EQ string columns (335 MB CSV):

| Metric | Before (in-memory compile) | After (streaming) |
|---|---|---|
| Compile time | 58-85 s | 45 s (34.9 s under a 256 MB heap cap) |
| Compile completes in 256 MB heap | no (table alone is ~1.5 GB of strings) | yes |
| Artifact representable | ≤ 2 GB only | unbounded (per-column < 2 GB) |

Trade-offs accepted:

- The source is parsed twice; two CSV passes cost less than the string churn they replace.
- Temporary disk usage roughly equals the artifact size during compilation (column
  temporaries are deleted as each column is stitched).
- Artifacts from formats 1.x-3.x must be recompiled.
