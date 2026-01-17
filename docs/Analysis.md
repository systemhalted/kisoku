# Completeness Check

## Resolved Issues

- ~~**Loader Missing**~~: **RESOLVED** - `CsvRulesetLoader` and `BinaryArtifactReader` now fully implemented.
  The ServiceLoader configuration correctly points to the working implementation.

- **JSON/DB formats**: Only CSV is currently supported; JSON/DB formats are declared but not implemented
  (see `TableFormat.java`, `DecisionTableSources.java`). This is documented as out-of-scope for M3.

## Open Issues (Performance Critical)

- **CRITICAL: Indexed evaluation is missing** - evaluation scans every row O(n) and `LoadOptions.prewarmIndexes`
  is unused. This prevents meeting PRD p95 < 250ms for 5M+ row tables.
  - Files: `LoadedRulesetImpl.java`, `LoadOptions.java`
  - **Target: M4 milestone**

- **TEST_ column toggle** - Test-only columns are correctly included in artifacts with flag `0x02` but there
  is no load/eval-time toggle to include them in evaluation output. They are always excluded at evaluation time.
  - File: `LoadedRulesetImpl.java`
  - **Status**: Working as designed per PRD (test columns for validation only)

- **Artifact layout partially implemented** - Column `dataOffset` is computed but index sections do not exist.
  This is consistent with M4 being the indexing milestone.
  - Files: `CsvRulesetCompiler.java`, `BinaryArtifactReader.java`, `docs/artifact-format.md`

# Heap Budget Check

## Current Implementation

- **Compilation**: The compiler materializes the full table in memory and builds the artifact in a `byte[]`.
  At typical scale (5M rows × 80 columns), compilation may approach or exceed 1GB heap.
  - Files: `CsvRulesetCompiler.java`, `CompiledRulesetImpl.java`
  - **Mitigation**: Compile on machines with sufficient heap; compiled artifacts are reusable.

- **Loaded rulesets**: Column data is stored in `int[]`/`short[]` arrays. For 5M rows × 82 columns × 4 bytes,
  this is ~1.6GB before bitmaps and overhead.
  - Files: `ScalarColumnDecoder.java`, `RangeColumnDecoder.java`, `SetMembershipColumnDecoder.java`
  - **Status**: Memory tests verify behavior; true off-heap targeted for M4.

- **Memory-map limitation**: `LoadOptions.memoryMap()` uses `ByteBuffer.allocateDirect()`, not true
  `FileChannel.map()`. The artifact is copied into a direct buffer, but column data still ends up on heap.
  - File: `CsvRulesetLoader.java`
  - **Target: M4** - Implement true file-backed memory mapping with lazy column loading.

- **String dictionaries**: Dictionary maps are heap-resident and scale with unique string values.
  - Files: `StringDictionary.java`, `StringDictionaryReader.java`
  - **Status**: Acceptable for most workloads; large dictionaries may require optimization.

## Memory Test Suite (M5)

The following tests verify memory behavior and are gated behind system properties:

| Test Class | Property | Purpose |
|------------|----------|---------|
| `HeapBudgetMemoryTest` | `kisoku.runMemoryTests` | Verifies heap < 1GB at 5M rows |
| `PerEvaluationMemoryTest` | `kisoku.runMemoryTests` | Verifies bounded per-eval allocation |
| `BulkEvaluationMemoryTest` | `kisoku.runMemoryTests` | Verifies bulk mode isolation |
| `ConcurrentEvaluationMemoryTest` | `kisoku.runMemoryTests` | Verifies thread-safety under load |
| `OffHeapMemoryTest` | `kisoku.runMemoryTests` | Verifies direct buffer usage |

Run with: `mvn -Dkisoku.runMemoryTests=true test`
