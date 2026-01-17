# Plan of Execution

## Purpose
Implement a Java library for the decision-table rule engine described in `docs/PRD.md`, with explicit lifecycle stages, deterministic runtime behavior, and scale/performance targets.

## Scope Summary
- Support decision tables from CSV in phase 1; JSON and database sources follow later.
- Compile rules into an execution-optimized representation with dual artifacts (test-inclusive and production).
- Load compiled rulesets into an immutable, shareable runtime form with indexed evaluation.
- Provide single and bulk evaluation modes with strict isolation and determinism.
- Meet JVM heap and latency targets at the defined workload scale.
- Ship as a library (no service) with a stable public API, versioned artifacts, and usage documentation.

## Execution Plan
1) **Clarify semantics and inputs**
   - Define supported column types, operator tokens (including aliases), and null/missing value behavior.
   - Specify rule priority/selection (e.g., first-match ordering) and tie-breaking rules.
   - Define test-only markers and validation errors for invalid tables.
   - Specify input merge rules for base + variant in bulk evaluation.
   - Document output contract (required vs optional outputs, defaults, error handling).
   - Define trimming and case-sensitivity rules.
   - Define the type map contract for inputs and outputs (including `DATE`/`TIMESTAMP` variants).

2) **Define library API and architecture**
   - Public API packages: `api`, `compiler`, `runtime`, `io`.
   - Define interfaces: `DecisionTableSource`, `RulesetValidator`, `RulesetCompiler`,
     `CompiledRuleset`, `RulesetLoader`, `LoadedRuleset`, `Evaluator`.
   - Define lifecycle flow: validate -> compile -> load -> activate -> evaluate.
   - Define configuration objects and error types (validation, compile, load, evaluate).

3) **Design storage and indexing**
   - Choose a compact columnar representation (dictionary encoding for strings).
   - Define index types per operator (equality hash, range index for numerics).
   - Specify serialized layout for compiled rulesets (versioned header + sections).
   - Decide compile-time vs load-time index construction and caching.

4) **Implement streaming CSV parser and validator**
   - Build a custom CSV reader that splits on commas outside parentheses.
   - Validate header row, operator row, and column metadata in a single pass.
   - Enforce `PRIORITY` required when the column exists.
   - Enforce output rules: `SET` columns allowed to be blank but at least one output per row.
   - Collect validation issues with row/column context and cap error counts.

5) **Implement compiler and loader**
   - Stream CSV rows into columnar blocks without per-row heap objects.
   - Define `ColumnSpec` and operator-specific storage (IN/NOT IN lists, BETWEEN ranges, SET outputs).
   - Compile dual artifacts (test-inclusive and production) and serialize to bytes/files.
   - Implement loader for immutable runtime state, with memory mapping if needed.
   - Build or hydrate indexes at load time if not persisted.

6) **Implement runtime evaluation**
   - Indexed candidate selection and condition evaluation in deterministic order.
   - Single evaluation API returning outputs + match metadata.
   - Bulk evaluation API with per-variant isolation and shared base input.
   - Thread-safe, lock-free reading on loaded rulesets.

7) **Library packaging and documentation**
   - Maven `jar` packaging, semantic versioning, minimal dependencies.
   - Javadoc for public APIs and examples of CSV/JSON sources.
   - README usage snippets and sample decision tables under `examples/`.

8) **Testing and performance validation**
   - Unit tests for parsing, validation, compilation, indexing, and semantics.
   - Integration tests for lifecycle and dual artifact correctness.
   - Determinism tests under concurrency; bulk isolation tests.
   - Benchmarks for p95 latency, bulk throughput, and heap usage.

## Milestones
- [x] M1: Specified rule semantics + public API draft.
- [x] M2: Streaming CSV parser + validator + column specs.
- [x] M3: Compiler + serialized artifact format + loader + evaluation.
  - [x] Compiler implemented (`CsvRulesetCompiler`)
  - [x] Binary artifact format implemented per `docs/artifact-format.md`
  - [x] Loader implemented (`CsvRulesetLoader`, `BinaryArtifactReader`)
  - [x] Single and bulk evaluation implemented (`LoadedRulesetImpl`)
  - [x] Type coercion and operator matching implemented
- [ ] M4: Indexed evaluation + true memory-mapping.
  - [ ] Build equality indexes for EQ/IN/NE/NOT_IN columns
  - [ ] Build range indexes for GT/GTE/LT/LTE/BETWEEN columns
  - [ ] Implement `FileChannel.map()` for file-backed artifacts
  - [ ] Lazy-load column sections on demand
  - **Status**: Current evaluation uses O(n) linear scan; must index for PRD targets
- [ ] M5: Memory test suite + heap budget verification.
  - [x] `MemoryTestUtils` and `MemorySnapshot` utilities
  - [x] Heap budget tests (`HeapBudgetMemoryTest`)
  - [x] Per-evaluation memory tests (`PerEvaluationMemoryTest`)
  - [x] Bulk evaluation memory tests (`BulkEvaluationMemoryTest`)
  - [x] Concurrent evaluation tests (`ConcurrentEvaluationMemoryTest`)
  - [x] Off-heap verification tests (`OffHeapMemoryTest`)
  - [ ] Benchmarks for p95 latency targets
- [ ] M6: Library docs + examples packaged.
