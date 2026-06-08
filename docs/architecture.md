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
- `PRIORITY` values are required when the column exists.
- Output (`SET`) cells may be blank, but at least one output must be non-blank per row.
- Compile against the client-provided schema (inputs and outputs).
- Enforce type-specific constraints per `ColumnType` (STRING, INTEGER, DECIMAL,
  BOOLEAN, DATE, TIMESTAMP).

## Operator Storage (Conceptual)
- `RULE_ID`: dictionary id or string offsets per row.
- `PRIORITY`: `int[]` per row.
- `BETWEEN_*`/`NOT_BETWEEN_*`: `minId[]`, `maxId[]`, `hasCondition` bitset.
- `IN`/`NOT_IN`: `listOffsets[]`, `listLengths[]`, `listValueIds[]`,
  `hasCondition` bitset.
- `SET` outputs: `valueId[]`, `hasValue` bitset.

## Artifact Layout (Conceptual)
- Header: magic, format version, artifact kind, schema hash, byte order.
- Dictionaries: string/value tables.
- Column blocks: encoded input/output columns.
- Rule order: deterministic evaluation order.
- Index sections: equality and range indexes per column.
- Optional diagnostics section for test artifacts.

## Compiler Streaming Checklist
- Read and validate header row + operator row.
- Build `ColumnSpec` list once and reuse it for row processing.
- Stream rows:
  - Validate per-row constraints (RULE_ID, PRIORITY, output presence).
  - Encode operands to dictionaries and append to column blocks.
- Flush column blocks and dictionaries to the artifact.
- Emit test-inclusive and production artifacts with consistent schema hashes.

## Loading Strategy

- `load(compiled, ...)` and `load(Path, ...)` are both supported via
  `BinaryArtifactReader`.
- `LoadOptions.memoryMap()` with `load(Path)` maps the artifact file directly
  (`FileChannel.map()`); `onHeap()` uses a heap buffer. In both cases column data
  is read **lazily through the buffer** by the decoders rather than copied into
  per-column heap arrays, keeping large tables off-heap to meet the <1GB budget.
- Indexes are built at load time (eagerly with `withPrewarmIndexes(true)`, the
  default, or lazily otherwise).
- `LoadedRuleset` is immutable and thread-safe for concurrent evaluation;
  `close()` releases the buffer/mapping.

## Indexing Strategy

Indexed candidate filtering is implemented (not a linear scan). Coverage:

- **Equality index**: value → bitmap for `EQ` columns.
- **Comparison index**: sorted-threshold bitmaps for `GT`, `GTE`, `LT`, `LTE`.
- **Set-membership index**: inverted value → rows bitmap for `IN`, `NOT_IN`
  (`NOT_IN` served by bitmap complement). See ADR-0009.
- **Candidate selection**: start from "all rows," fetch each indexed input
  column's candidate bitmap, intersect (`AND`) into the running set, then verify
  survivors in deterministic rule order.
- **Deterministic rule selection**: fixed row order or explicit `PRIORITY`.

Range operators (`BETWEEN_*`) are not yet indexed and fall back to verification.

### Performance Impact
- Bitmap intersection reduces the candidate set before per-row verification,
  meeting the PRD p95 target where linear scan could not at 5M rows.

## Evaluation Path

- Normalize inputs once and reuse in both single and bulk modes.
- Apply base input, then overlay variant inputs per evaluation.
- Narrow candidates via index bitmap intersection, then verify candidate rows in
  deterministic order (priority or first-match).
- Type coercion via `TypeCoercion` handles input value conversions; decoders also
  support a coerced-int match path read directly from the buffer.
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
