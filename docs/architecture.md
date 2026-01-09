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

## Canonical Model
- Column definitions: inputs, outputs, metadata, and test-only columns.
- Rule rows expressed as normalized conditions + outputs.
- Explicit row ordering to support deterministic selection rules.
- Reserved column names and keywords defined by the library are ALL CAPS.
- Column roles are determined by prefixes: `IN_` for inputs, `OUT_` for outputs,
  and `TEST_` for test-only columns.
- Reserved columns include `RULE_ID` and `PRIORITY` (default priority column).
- CSV sources use two header rows: names, then operators (fixed per column).

## Compilation
- Normalize values to typed forms and encode strings via dictionaries.
- Build per-column metadata (types, ranges, nullability, test flags).
- Produce two artifacts: one includes test-only columns; the production artifact
  removes them and reindexes outputs accordingly.
- Persist a stable, versioned binary layout with checksums.
- Parse operator values in cells: `BETWEEN`/`NOT BETWEEN` use `(min,max)`,
  `IN`/`NOT IN` use `(A,B,C)`, and blank cells mean no condition.

## Artifact Layout (Conceptual)
- Header: magic, format version, artifact kind, schema hash, byte order.
- Dictionaries: string/value tables.
- Column blocks: encoded input/output columns.
- Rule order: deterministic evaluation order.
- Index sections: equality and range indexes per column.
- Optional diagnostics section for test artifacts.

## Loading Strategy
- Load artifacts into read-only buffers; prefer memory mapping for large tables.
- Build or hydrate indexes at load time if not fully persisted.
- Expose a `LoadedRuleset` that is immutable and shareable across threads.
- Provide `close()` to release mapped resources.

## Indexing Strategy
- Equality index: value -> bitset or row list for discrete columns.
- Range index: sorted arrays + binary search for numeric comparisons.
- Candidate selection: intersect indexed candidates; fallback to scan only when
  index coverage is insufficient.
- Deterministic rule selection using fixed row order or explicit priority.

## Evaluation Path
- Normalize inputs once and reuse in both single and bulk modes.
- Apply base input, then overlay variant inputs per evaluation.
- Evaluate candidate rows in deterministic order. If a priority column exists,
  select by priority first; otherwise apply first-match row order.
- Return outputs plus optional diagnostics (rule id, matched conditions).

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
