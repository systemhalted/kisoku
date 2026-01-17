Functional requirements

1. Decision table support
   The rule engine must support authoring and execution of rules using decision tables that define multiple input conditions and multiple output fields per rule. The rules may be written in CSV, JSON or Database

2. Test cells and dual artifacts
   Decision tables must support test-only cells or columns for validating additional input and output parameters. When test cells are present, test-only columns are included in all artifacts with a flag (0x02) for evaluation-time control. The loader/evaluator can optionally exclude them, allowing users to run tests in production against real inputs. 

3. Ruleset compilation to an execution-ready representation
   The rule engine must compile decision tables into a compact, execution-optimized representation. Runtime execution must operate on the compiled representation rather than interpreting the source format.

4. Full-table loading prior to execution
   The rule engine must support loading the entire compiled decision table into an execution-ready state before evaluation begins. “Loaded” means all rows and columns are locally available for deterministic evaluation without remote dependency calls.

5. Indexed evaluation
   The engine must support indexed evaluation to avoid full table scans for most evaluations. Indexes must be created at compile time or load time and used during runtime to narrow candidate rule sets efficiently.

6. Bulk evaluation mode
   The engine must support bulk evaluation where a shared base input is evaluated against a collection of variant inputs in a single invocation, producing one independent decision result per variant.

7. Determinism and evaluation isolation
   Given the same ruleset version and the same inputs, rule execution must produce the same outputs. In bulk and parallel modes, variant-specific inputs, intermediate state, and outputs must not leak across evaluations.

8. Immutable, shareable loaded rulesets
   Once a ruleset is loaded, it must be immutable and safely shareable across threads and requests.

9. Explicit ruleset lifecycle
   The engine must provide explicit lifecycle operations for validation, compilation, loading, and activation of a ruleset, with clear separation between lifecycle costs and runtime evaluation.

Non functional requirements

1. Decision table scale
   The engine must support very large decision tables, including tables with millions of rows, while preserving deterministic evaluation behavior.

2. Memory efficiency under JVM constraints
   For JVM deployments, the engine must support the typical workload while keeping Java heap usage under 1 GB. This heap limit applies to the JVM heap only. The engine may use off-heap memory or memory-mapped storage to hold loaded tables and indexes.

3. Bounded per-evaluation working set
   Per-evaluation runtime memory usage must be bounded and must not scale with total table size. Per-evaluation allocations must be minimal to avoid GC pressure under concurrency.

4. Parallel execution performance
   The engine must support concurrent execution across multiple requests and complete the defined parallel workload within under one minute, assuming configured parallelism and a loaded ruleset.

5. Bulk execution performance
   For the defined bulk workload, the engine must complete bulk evaluation within the stated time targets while maintaining isolation and determinism.

6. Ruleset lifecycle performance
   Ruleset compilation and loading must complete within the stated time targets for the typical workload, with predictable resource usage.

7. Predictable latency after load
   After a ruleset is loaded and warmed, runtime evaluation latency must meet the stated percentile targets for the typical workload.

Definition of typical workload

A “typical workload” is the production operating envelope the system must meet by default on a single instance, for one actively loaded ruleset, after warmup.

1. Decision table characteristics
   Rows: 5,000,000 rows typical, with support up to 20,000,000 rows
   Input columns: 60 typical, up to 120
   Output columns: 20 typical, up to 50
   Metadata columns: allowed, not used in runtime evaluation

2. Input payload characteristics
   Shared base input: 200 to 800 scalar fields typical
   Variant input: 20 to 200 scalar fields typical
   Serialized input size: 5 KB to 100 KB per evaluation typical

3. Invocation shapes
   Single evaluation: one base input produces one decision output
   Bulk evaluation: one base input plus 10,000 variants typical, with support up to 100,000 variants

4. Concurrency targets
   Concurrent evaluations: 200 parallel evaluations typical, with support up to 500 depending on CPU and I O characteristics

5. Runtime performance targets for a loaded ruleset
   Single evaluation latency: p95 under 250 ms
   Bulk evaluation completion: 10,000 variants under 60 seconds
   Parallel workload completion: 200 concurrent evaluations sustained and completed within the 60 second window for the defined traffic shape

6. Ruleset lifecycle targets
   Compilation time for a typical ruleset: under 60 seconds
   Load plus index preparation time for a typical ruleset: under 60 seconds
   After load, the engine must not require re-parsing source formats during evaluation

7. Memory targets
   JVM heap: under 1 GB while meeting the above runtime targets
   Loaded table and indexes may reside off-heap or via memory-mapped files to satisfy the heap budget
   The engine must not represent the full table as per-cell or per-row JVM objects on the heap for million-row workloads.

