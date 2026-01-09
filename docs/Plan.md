# Plan of Execution

## Purpose
Implement a Java library for the decision-table rule engine described in `PRD.md`, with explicit lifecycle stages, deterministic runtime behavior, and scale/performance targets.

## Scope Summary
- Support decision tables from CSV, JSON, and database sources.
- Compile rules into an execution-optimized representation with dual artifacts (test-inclusive and production).
- Load compiled rulesets into an immutable, shareable runtime form with indexed evaluation.
- Provide single and bulk evaluation modes with strict isolation and determinism.
- Meet JVM heap and latency targets at the defined workload scale.
- Ship as a library (no service) with a stable public API, versioned artifacts, and usage documentation.

## Execution Plan
1) **Clarify semantics and inputs**
   - Define supported column types, operators, and null/missing value behavior.
   - Specify rule priority/selection (e.g., first-match ordering) and tie-breaking rules.
   - Define test-only markers and validation errors for invalid tables.
   - Specify input merge rules for base + variant in bulk evaluation.
   - Document output contract (required vs optional outputs, defaults, error handling).

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

4) **Implement parsers, compiler, and loader**
   - CSV and JSON loaders; DB source adapter interface (user-provided implementation).
   - Build a canonical in-memory model and validation pipeline.
   - Compile dual artifacts (test-inclusive and production) and serialize to bytes/files.
   - Implement loader for immutable runtime state, with memory mapping if needed.

5) **Implement runtime evaluation**
   - Indexed candidate selection and condition evaluation in deterministic order.
   - Single evaluation API returning outputs + match metadata.
   - Bulk evaluation API with per-variant isolation and shared base input.
   - Thread-safe, lock-free reading on loaded rulesets.

6) **Library packaging and documentation**
   - Maven `jar` packaging, semantic versioning, minimal dependencies.
   - Javadoc for public APIs and examples of CSV/JSON sources.
   - README usage snippets and sample decision tables under `examples/`.

7) **Testing and performance validation**
   - Unit tests for parsing, validation, compilation, indexing, and semantics.
   - Integration tests for lifecycle and dual artifact correctness.
   - Determinism tests under concurrency; bulk isolation tests.
   - Benchmarks for p95 latency, bulk throughput, and heap usage.

## Milestones
- M1: Specified rule semantics + public API draft.
- M2: Canonical model + compiler + serialized artifact format.
- M3: Loader + immutable runtime + indexed evaluation.
- M4: Bulk evaluation + concurrency safety + deterministic outputs.
- M5: Benchmarks + memory/latency tuning to PRD targets.
- M6: Library docs + examples packaged.
