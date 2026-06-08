# Designing Kisoku: Why We Chose Compiled, Indexed Decision Tables in Java

Status: June 7, 2026.

Kisoku is built around one core idea: decision tables should be authored in a human-friendly format, then compiled into a runtime-friendly format. That sounds simple, but it drives almost every major design decision in this repository.

This post explains those decisions, the tradeoffs behind them, and what is implemented now versus what is still planned.

## The Constraints We Designed For

The design is anchored to hard product constraints in the [PRD](../PRD.md):

- Large tables (typical 5,000,000 rows, support up to 20,000,000).
- Deterministic behavior under single, bulk, and concurrent evaluation.
- Bounded per-evaluation memory, with JVM heap under 1 GB for typical workloads.
- Explicit lifecycle phases: validate, compile, load, and evaluate.

Those constraints ruled out "just parse CSV and scan rows on every request" very early.

## Decision 1: Lifecycle-First Architecture

Kisoku uses an explicit lifecycle:

1. Validate source + schema.
2. Compile to a binary artifact.
3. Load artifact into an immutable runtime form.
4. Evaluate inputs against loaded state.

Why this decision:

- It isolates expensive work (parsing, normalization, indexing) from request-time work.
- It makes runtime behavior predictable: evaluation does not need source parsing.
- It gives clean failure boundaries (`ValidationResult`, compile/load/eval exceptions).

Where this shows up:

- API contracts: [`Kisoku`](../../kisoku-api/src/main/java/in/systemhalted/kisoku/api/Kisoku.java)
- Validation: [`CsvRulesetValidator`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/csv/CsvRulesetValidator.java)
- Compilation: [`CsvRulesetCompiler`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/compiler/CsvRulesetCompiler.java)
- Loading/evaluation: [`CsvRulesetLoader`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/CsvRulesetLoader.java), [`LoadedRulesetImpl`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/LoadedRulesetImpl.java)

Related ADR: [ADR-0001](../adr/0001-multi-module-api-runtime-split.md)

## Decision 2: Stable API, Swappable Runtime

Kisoku is split into:

- `kisoku-api`: public contracts and data types.
- `kisoku-runtime`: implementation, discovered via `ServiceLoader`.

Why this decision:

- Keep internals (encoders, decoders, indexes) out of the public contract.
- Allow alternative runtimes later without changing user code.
- Keep application code depending on stable interfaces.

This is an intentional plugin-style architecture, not just packaging convenience.

Related ADR: [ADR-0001](../adr/0001-multi-module-api-runtime-split.md)

## Decision 3: CSV is an Authoring Format, Not an Execution Format

Kisoku currently supports CSV sources (JSON/database are in the enum but not implemented yet).

The CSV format is intentionally opinionated:

- Row 1: column names.
- Row 2: operators (fixed per column).
- Data rows: operands only.

Why this decision:

- Non-engineers can author and review tables directly.
- The operator row removes ambiguity and avoids per-cell operator parsing at runtime.
- It maps cleanly to columnar encoding.

Important parser/validator choices:

- Streaming parser with parentheses-depth tracking for `(A,B,C)` style cells.
- Whitespace trimming and blank-cell preservation.
- Alias normalization (`>=` -> `GTE`, `BETWEEN` -> `BETWEEN_INCLUSIVE`, etc.).
- Validation that enforces schema membership and row-level output presence.

Where this shows up:

- Parser: [`StreamingCsvRowReader`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/csv/StreamingCsvRowReader.java)
- Operator normalization: [`Operator`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/csv/Operator.java)
- Validator: [`CsvRulesetValidator`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/csv/CsvRulesetValidator.java)

Related ADRs:

- [ADR-0006](../adr/0006-operator-aliasing-normalization.md)
- [ADR-0007](../adr/0007-streaming-csv-parser.md)

## Decision 4: Compile to a Columnar Binary Artifact

At runtime, Kisoku evaluates compiled bytes, not CSV text.

Artifact structure (high level):

- Header (version, artifact kind, offsets, counts).
- String dictionary.
- Column definitions.
- Columnar rule data.
- Rule order index.

Why this decision:

- Compact storage and predictable decoding.
- Better cache locality for column-wise filtering.
- A single persisted format that can be loaded repeatedly.

Design details:

- Strings are dictionary-encoded to integer IDs.
- Scalar/range/set operators use different encoded layouts.
- Artifact kind is explicit (`PRODUCTION` or `TEST_INCLUSIVE`).

Where this shows up:

- Format spec: [`docs/artifact-format.md`](../artifact-format.md)
- Writer: [`BinaryArtifactWriter`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/compiler/BinaryArtifactWriter.java)
- Reader: [`BinaryArtifactReader`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/BinaryArtifactReader.java)

Related ADR: [ADR-0002](../adr/0002-columnar-storage-dictionary-compression.md)

## Decision 5: Keep Test Columns in Artifacts, Exclude at Evaluation

`TEST_` columns are retained during compilation for both artifact kinds and marked with flag `0x02`.

Why this decision:

- One source table can support both production behavior and test/diagnostic workflows.
- We avoid creating and maintaining separate rule files.

Current behavior nuance:

- Compiler includes test columns in metadata and artifact bytes.
- Evaluator skips test-only columns in both input matching and output projection.

Where this shows up:

- Compiler behavior: [`CsvRulesetCompiler`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/compiler/CsvRulesetCompiler.java)
- Evaluation skip: [`LoadedRulesetImpl`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/LoadedRulesetImpl.java)
- Functional assertion: [`DecisionTableLifecycleTest`](../../kisoku-runtime/src/test/java/in/systemhalted/kisoku/functional/DecisionTableLifecycleTest.java)

Related ADR: [ADR-0008](../adr/0008-dual-artifact-architecture.md)

## Decision 6: Prefer Indexed Candidate Filtering Over Full Scans

For large tables, evaluating every row for every request does not meet PRD latency goals. Kisoku therefore supports indexed filtering at load/evaluation time.

Current index coverage:

- `EQ` via hash-based bitmap index.
- `GT`, `GTE`, `LT`, `LTE` via sorted-threshold bitmap index.
- `IN`, `NOT_IN` via an inverted set-membership index (value -> rows bitmap, with `NOT_IN` served by bitmap complement).

How it works:

1. Start with "all rows" bitmap.
2. For each indexed input column, fetch candidate bitmap for that input value.
3. Intersect (`AND`) into the current candidate set.
4. Iterate candidates in deterministic rule order and run full-match verification.

Why bitmaps:

- Intersections are very fast word-level operations.
- They compose naturally across multiple columns.
- Blank/no-condition rows can be represented once and OR-ed into results.

Where this shows up:

- Eval path: [`LoadedRulesetImpl`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/LoadedRulesetImpl.java)
- Index factory: [`ColumnIndexBuilder`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/ColumnIndexBuilder.java)
- Bitmap ops: [`CandidateBitmap`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/index/CandidateBitmap.java)
- Equality index: [`EqualityIndex`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/index/EqualityIndex.java)
- Comparison index: [`ComparisonIndex`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/index/ComparisonIndex.java)
- Set membership index: [`SetMembershipIndex`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/index/SetMembershipIndex.java)
- Parity tests (indexed vs linear): [`IndexedEvaluationTest`](../../kisoku-runtime/src/test/java/in/systemhalted/kisoku/functional/IndexedEvaluationTest.java)

Related ADRs:

- [ADR-0005](../adr/0005-bitmap-based-indexing-strategy.md)
- [ADR-0009](../adr/0009-set-membership-index-strategy.md)

## Decision 7: Immutability and Isolation Over Stateful Runtime Tricks

`LoadedRuleset` is designed as immutable shared state. Evaluations build request-local state and never mutate loaded rule structures.

Why this decision:

- Concurrent evaluation is simpler and safer.
- Determinism is easier to reason about.
- Memory behavior is more predictable under load.

Bulk mode design:

- `evaluateBulk(base, variants)` merges base + variant per variant.
- Variant values override base values.
- Variants are evaluated independently and returned in input order.

Where this shows up:

- Implementation: [`LoadedRulesetImpl`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/LoadedRulesetImpl.java)
- Bulk functional test: [`DecisionTableBulkEvaluationTest`](../../kisoku-runtime/src/test/java/in/systemhalted/kisoku/functional/DecisionTableBulkEvaluationTest.java)
- Concurrency/memory tests: [`ConcurrentEvaluationMemoryTest`](../../kisoku-runtime/src/test/java/in/systemhalted/kisoku/memory/ConcurrentEvaluationMemoryTest.java)

## Decision 8: Expose Load Strategy as an API Choice

`LoadOptions` makes load behavior explicit:

- `memoryMap()` (default): direct `ByteBuffer` path.
- `onHeap()`: heap-backed `ByteBuffer` path.
- `withPrewarmIndexes(boolean)`: control index construction at load time.

Why this decision:

- Different workloads prefer different startup/memory tradeoffs.
- It keeps operational choices in user code, not hard-coded in runtime.

Current behavior:

- `load(Path)` now memory-maps the artifact file directly via `FileChannel.map()`; the bytes stay off-heap and decoders read column data lazily through the mapped buffer rather than copying sections eagerly.
- The same compiled `.kss` artifact can be written once and loaded identically across processes/pods.

Where this shows up:

- API: [`LoadOptions`](../../kisoku-api/src/main/java/in/systemhalted/kisoku/api/loading/LoadOptions.java)
- Loader behavior: [`CsvRulesetLoader`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/CsvRulesetLoader.java)
- Memory tests: [`OffHeapMemoryTest`](../../kisoku-runtime/src/test/java/in/systemhalted/kisoku/memory/OffHeapMemoryTest.java)

Related ADR: [ADR-0003](../adr/0003-direct-memory-mapped-buffer-strategy.md)

## Decision 9: Two Execution Paths, One Immutable Ruleset

Single-eval and high-volume scoring pull in opposite directions. A REST request wants the lowest possible per-call overhead; scoring millions of inputs from a file or stream wants raw throughput. Rather than force one kernel to serve both, Kisoku is moving toward a dual execution model over the same immutable ruleset.

The two paths:

- **Single-eval (latency):** the existing `LoadedRuleset.evaluate(DecisionInput)` path, unchanged — indexed, immutable, thread-safe, sub-millisecond of compute.
- **Vectorized bulk (throughput):** a columnar kernel that takes pre-coerced `int` codes (no `Map`, no boxing per input), prunes via the most selective indexed columns into a reusable per-thread candidate bitmap with early-exit, then verifies the few survivors in priority order. Parallelism is caller-owned (a supplied `Executor`); the engine never spins its own pool.

Why this decision:

- The row-at-a-time `Map`-based call is structurally too costly for millions/sec regardless of threading; columnar input plus selectivity pruning cut the per-eval cost, and parallelism multiplies it.
- Both paths reuse the same indexes (ADR-0005/0009) as a pre-filter and the same off-heap decoders as the verify path, so correctness is shared.

Current status (scalar first):

- The scalar bulk kernel is implemented internally (no public API yet), proven by a parity oracle: bulk results must equal single-eval results across operators, priority, indexing on/off, and parallelism. SIMD acceleration of the bitmap `AND` is a planned drop-in second step.

Where this shows up:

- Kernel: [`ColumnarBulkKernel`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/ColumnarBulkKernel.java)
- Package-private entry point: [`LoadedRulesetImpl.bulkKernel()`](../../kisoku-runtime/src/main/java/in/systemhalted/kisoku/runtime/loader/LoadedRulesetImpl.java)
- Parity oracle: [`ColumnarBulkKernelParityTest`](../../kisoku-runtime/src/test/java/in/systemhalted/kisoku/runtime/loader/ColumnarBulkKernelParityTest.java)

Related ADR: [ADR-0010](../adr/0010-dual-execution-model.md) (Proposed)

## Implemented vs Planned (Current Snapshot)

| Area | Implemented now | Planned next |
|---|---|---|
| Source formats | CSV validate/compile/load | JSON and database sources |
| Lifecycle split | Explicit validate/compile/load/evaluate | Additional diagnostics and tooling around lifecycle |
| Runtime indexing | `EQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN` indexes with parity tests | Index support for `BETWEEN_*` |
| Load strategy | On-heap, direct-buffer, and file-backed `load(Path)` mmap with lazy section reads | Further off-heap/streaming refinements |
| Execution model | Indexed single-eval; scalar vectorized bulk kernel (internal), parity-tested | Public bulk API, columnar input/output adapters, SIMD `AND` |
| Test columns | `TEST_` columns persisted with `0x02` flag; skipped during evaluation | Optional evaluation-time inclusion controls |
| Scale verification | Gated scale + memory test suites | Regularized benchmark reporting against PRD latency targets |

## Tradeoffs We Knowingly Accepted

1. Compilation simplicity over minimum compile-time memory.

The compiler uses a streaming row reader, but currently collects data rows into memory before encoding. This keeps the implementation straightforward and deterministic today, at the cost of higher compile-time memory pressure for very large inputs.

2. Fast progress over full operator-index coverage.

The index layer started with operators that provide immediate runtime wins, then added set membership (`IN`/`NOT_IN`). Range variants (`BETWEEN_*`) are still pending.

3. API clarity over hidden defaults.

Load and compile behavior is explicit (`CompileOptions`, `LoadOptions`) rather than implicit global configuration, which is slightly more verbose for users but easier to reason about in production.

## Closing

Kisoku's architecture is intentionally constraint-driven:

- human-authorable rule sources,
- compiled execution artifacts,
- immutable loaded state,
- index-assisted deterministic evaluation.

The short version of the design philosophy is: pay lifecycle costs once, keep runtime behavior predictable, and preserve enough abstraction to evolve runtime internals without breaking API consumers.

## Further Reading

- Product requirements: [`docs/PRD.md`](../PRD.md)
- Architecture overview: [`docs/architecture.md`](../architecture.md)
- Artifact format: [`docs/artifact-format.md`](../artifact-format.md)
- API notes: [`docs/api.md`](../api.md)
- ADRs:
  - [ADR-0001](../adr/0001-multi-module-api-runtime-split.md)
  - [ADR-0002](../adr/0002-columnar-storage-dictionary-compression.md)
  - [ADR-0003](../adr/0003-direct-memory-mapped-buffer-strategy.md)
  - [ADR-0004](../adr/0004-sealed-interface-decoder-hierarchy.md)
  - [ADR-0005](../adr/0005-bitmap-based-indexing-strategy.md)
  - [ADR-0006](../adr/0006-operator-aliasing-normalization.md)
  - [ADR-0007](../adr/0007-streaming-csv-parser.md)
  - [ADR-0008](../adr/0008-dual-artifact-architecture.md)
  - [ADR-0009](../adr/0009-set-membership-index-strategy.md)
  - [ADR-0010](../adr/0010-dual-execution-model.md)
