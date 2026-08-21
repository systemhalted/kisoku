# Architecture Overview

## Requirements Anchors
The design must satisfy the PRD: compiled decision tables, dual artifacts
(test-inclusive and production), full-table load before execution, indexed
evaluation, bulk mode, determinism, immutable loaded rulesets, and JVM heap
under 1 GB with bounded per-evaluation working set.

## High-Level Flow
1) Source -> Parser -> Canonical Model
2) Validator -> Compile -> Compiled Artifact
3) Loader -> LoadedRuleset (immutable)
4) Evaluator -> Single or Bulk results

## CSV Parsing (Streaming)
- CSV is streamed and never fully loaded into memory.
- Cells can contain commas inside parentheses, so parsing must split on commas
  only when not inside parentheses.
- Values are trimmed before parsing and comparison.
- Empty cells are preserved; blank means "no condition" for inputs.
- Reject rows with unbalanced parentheses and report row/column context.

## Canonical Model
- Column definitions: inputs, outputs, metadata, and test-only columns.
- Rule rows expressed as normalized conditions + outputs.
- Explicit row ordering to support deterministic selection rules.
- Reserved column names and keywords defined by the library are ALL CAPS.
- Column roles are determined by the operator row: `SET` marks outputs, all
  other operators are inputs.
- Test-only columns are prefixed with `TEST_`.
- Reserved columns include `RULE_ID` and `PRIORITY` (default priority column, configurable via `CompileOptions`).
- CSV sources use two header rows: names, then operators (fixed per column).

## ColumnSpec (Conceptual)
- `name`: column name from header row.
- `operator`: operator token from the operator row.
- `isOutput`: true when operator is `SET`.
- `isTest`: true when name starts with `TEST_`.
- `index`: column position in the row array.

## Compilation
- Normalize values to typed forms and encode strings via dictionaries.
- Build per-column metadata (types, ranges, nullability, test flags).
- Produce two artifacts: both include test-only columns marked with flag `0x02`.
  The loader/evaluator can optionally exclude them at evaluation time.
- Persist a stable, versioned binary layout with checksums.
- Parse operator values in cells: `BETWEEN_*`/`NOT_BETWEEN_*` use `(min,max)`,
  `IN`/`NOT_IN` use `(A,B,C)`, and blank cells mean no condition.
- Normalize operator aliases (e.g., `>=` -> `GTE`, `BETWEEN` -> `BETWEEN_INCLUSIVE`) during compilation.
- `PRIORITY` values are required when the column exists; a blank ranks the row last.
- Output (`SET`) cells may be blank, but at least one output must be non-blank per row.
- Compile against the client-provided schema (inputs and outputs).
- Enforce type-specific constraints per `ColumnType` (STRING, INTEGER, DECIMAL,
  BOOLEAN, DATE, TIMESTAMP).

## Value Encoding
- Every column type reduces to one 64-bit **order-preserving code**, so comparing codes is
  comparing values. Strings encode as their rank in the *sorted* dictionary, decimals as the
  unscaled value at the column's scale, dates as epoch days, timestamps as epoch microseconds.
- Codes are doubled, leaving odd numbers to mean "strictly between two representable values".
  An input the compiler never saw (a string not in the dictionary, a decimal finer than the
  column's scale) takes such a code: it orders correctly and equals nothing.
- See `docs/artifact-format.md` for the stored layout.

## Operator Storage (Conceptual)
- `RULE_ID`: inline UTF-8 (byte offsets + blob), decoded on demand for the winning row only.
  Rule ids are unique per row, so they never enter the dictionary — keeping the dictionary,
  and load-time heap, independent of row count.
- `PRIORITY`: value code per row.
- `BETWEEN_*`/`NOT_BETWEEN_*`: `min[]`, `max[]` codes, `hasCondition` bitset.
- `IN`/`NOT_IN`: `listOffsets[]`, `listLengths[]`, `listValues[]` codes,
  `hasCondition` bitset.
- `SET` outputs: `value[]` codes, `hasValue` bitset.

## Artifact Layout (Conceptual)
- Header: magic, format version, artifact kind, schema hash, byte order.
- Dictionaries: string/value tables.
- Column blocks: encoded input/output columns.
- Rule order: deterministic evaluation order.
- Index sections: equality and range indexes per column.
- Optional diagnostics section for test artifacts.

## Compiler Streaming Pipeline
- **Pass 1** streams the CSV: header/operator validation, string dictionary (bounded by
  distinct values), per-column decimal scales, one priority int per row, row count.
- Evaluation order is a stable ascending argsort of the priorities (lower value = higher
  priority); identity when no priority applies.
- **Pass 2** streams the CSV again, encoding each cell to its order-preserving code and
  appending it to a per-column temporary file in source order.
- **Stitch** writes the artifact sequentially — header, dictionary, definitions, then each
  column: load that one column's temporary file, permute its rows into evaluation order in
  memory, emit its subsections, delete the temporary.
- Peak compile heap = dictionary + one int per row + the largest single column's data —
  independent of total table size. The source's `openStream()` must be re-openable.
- `CompiledRuleset` is file-backed; artifacts stream through 64-bit offsets and may exceed
  2 GB (each column's data stays under 2 GB).

## Loading Strategy

- `load(compiled, ...)` and `load(Path, ...)` are both supported via
  `BinaryArtifactReader`.
- `LoadOptions.memoryMap()` with `load(Path)` maps the artifact file directly
  (`FileChannel.map()`); `onHeap()` uses a heap buffer. In both cases column data
  is read **lazily through the buffer** by the decoders rather than copied into
  per-column heap arrays, keeping large tables off-heap to meet the <1GB budget.
- Indexes are built at load time into direct (off-heap) buffers (eagerly with
  `withPrewarmIndexes(true)`, the default, or not at all otherwise).
- `LoadedRuleset` is immutable and thread-safe for concurrent evaluation;
  `close()` releases the buffer/mapping.

## Indexing Strategy

Each indexable input column gets one **posting-list (CSR) index in a direct buffer off the
heap** (see ADR-0011): sorted distinct codes, per-code offsets, row-id postings, and the
blank-cell rows. Each present cell is stored exactly once, so index size is linear in the
column's data and independent of value skew.

- **Indexed operators**: `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE` (scalar), `IN`, `NOT_IN`
  (set membership). Negative operators index for exact candidate *counts* only — their match
  sets are complements and are never enumerated, but a zero count is a correct early exit.
- **Candidate selection**: every indexed column reports an exact candidate count for the
  input in O(log distinct); the most selective enumerable column drives — its candidate rows
  (postings slice plus blanks) are iterated and each is verified against all input columns
  through the decoders. Verification is exhaustive, so the driver choice is a pure
  optimization. Single evaluation and the bulk kernel share this matcher.
- **Deterministic rule selection**: fixed row order, or `PRIORITY` ascending — a lower value
  means a higher priority, so 1 outranks 2 — with ties broken by source order and unnumbered
  rows last. Rows are written to the artifact already in evaluation order, so the rule-order
  section stores the identity permutation over physical rows, and ascending row order is
  evaluation order during candidate enumeration.

Range operators (`BETWEEN_*`) are not yet indexed and fall back to verification.

### Performance Impact
- Driver-based candidate enumeration keeps per-evaluation work proportional to the most
  selective column's candidate count, not the table: matching-eval latency is flat across
  table sizes, per-evaluation allocation is constant, and index memory is off-heap and
  linear in the data.

## Evaluation Path

- Normalize inputs once and reuse in both single and bulk modes.
- Apply base input, then overlay variant inputs per evaluation.
- Narrow candidates via index bitmap intersection, then verify candidate rows in
  deterministic order (priority or first-match).
- Type coercion via `TypeCoercion` maps inputs into the order-preserving code domain;
  decoders also support a pre-coerced match path read directly from the buffer, carrying an
  explicit presence flag so an absent field is never mistaken for a supplied value.
- Return `DecisionOutput` with `ruleId()` and `outputs()` (plus optional
  diagnostics).

A separate internal scalar **columnar bulk kernel** (ADR-0010, package-private)
scores pre-coerced columnar batches for high-volume throughput; it is not yet
public API. The public bulk entry point remains `LoadedRuleset.evaluateBulk`.

## Concurrency and Isolation
- Loaded rulesets are immutable; no shared mutable state during evaluation.
- Per-evaluation data lives on the stack or in thread-local buffers.
- Bulk evaluations isolate variant state and outputs.

## Performance and Memory
- Columnar encoding and dictionary compression minimize heap usage.
- Indexes and rule data stored off-heap when possible.
- Evaluation working set remains bounded and independent of table size.
- Avoid per-row/per-cell Java objects for million-row workloads.

## Observability and Testing
- Surface lightweight counters for index hit rate and scan fallbacks.
- Unit tests for parsing/validation and rule semantics.
- Integration tests for lifecycle boundaries and artifact equivalence.
- Benchmarks for latency, bulk throughput, and memory targets.
