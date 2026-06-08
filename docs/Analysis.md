# Completeness Check

## Resolved Issues

- ~~**Loader Missing**~~: **RESOLVED** - `CsvRulesetLoader` and `BinaryArtifactReader` now fully implemented.
  The ServiceLoader configuration correctly points to the working implementation.

- ~~**Indexed evaluation missing**~~: **RESOLVED** - evaluation now narrows candidates via bitmap
  index intersection (`EqualityIndex` for EQ, `ComparisonIndex` for GT/GTE/LT/LTE, `SetMembershipIndex`
  for IN/NOT_IN) before verifying survivors in priority order. `prewarmIndexes` is honored at load.
  - Files: `LoadedRulesetImpl.java`, `ColumnIndexBuilder.java`, `loader/index/*`

- ~~**Memory-map limitation**~~: **RESOLVED** - `load(Path)` with `memoryMap()` uses true
  `FileChannel.map()`; decoders read column data lazily through the mapped buffer (off-heap) rather
  than copying sections into heap arrays.
  - Files: `CsvRulesetLoader.java`, decoder classes

## Open Issues

- **JSON/DB formats**: Only CSV is currently supported; JSON/DB formats are declared but not implemented
  (see `TableFormat.java`, `DecisionTableSources.java`). Roadmap Phase 5.

- **`NE` / `BETWEEN_*` not indexed**: these operators evaluate correctly but fall back to linear
  verification (no index pruning). A `RangeIntervalIndex` is roadmap Phase 3.
  - Files: `ColumnIndexBuilder.java`, `RangeColumnDecoder.java`

- **No benchmark harness**: PRD `p95 < 250ms` and `bulk-10K-variants < 60s` are not yet measured by a
  reproducible benchmark. Roadmap Phase 2.

- **TEST_ column toggle** - Test-only columns are correctly included in artifacts with flag `0x02` but there
  is no load/eval-time toggle to include them in evaluation output. They are always excluded at evaluation time.
  - File: `LoadedRulesetImpl.java`
  - **Status**: Working as designed per PRD (test columns for validation only)

# Heap Budget Check

## Current Implementation

- **Compilation**: The compiler materializes the full table in memory and builds the artifact in a `byte[]`.
  At typical scale (5M rows × 80 columns), compilation may approach or exceed 1GB heap.
  - Files: `CsvRulesetCompiler.java`, `CompiledRulesetImpl.java`
  - **Mitigation**: Compile on machines with sufficient heap; compiled artifacts are reusable.

- **Loaded rulesets**: Decoders read column data lazily through the mapped/direct buffer rather than
  inflating per-column `int[]`/`short[]` arrays, so loaded rule data stays off-heap. Index bitmaps
  remain on-heap.
  - Files: `ScalarColumnDecoder.java`, `RangeColumnDecoder.java`, `SetMembershipColumnDecoder.java`
  - **Status**: Memory tests (`OffHeapMemoryTest`) verify artifacts stay off-heap.

- **Memory-mapping**: `load(Path)` with `LoadOptions.memoryMap()` uses true `FileChannel.map()`;
  `onHeap()` uses a heap buffer. Column data is read on demand through the buffer in both cases.
  - File: `CsvRulesetLoader.java`

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
