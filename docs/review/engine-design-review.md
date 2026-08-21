# Kisoku Engine Review: Does this design work as a rule engine?

Scope: full read of `kisoku-api` and `kisoku-runtime` at commit `dd9257f`, plus
executable probes run against the built jars (compile → write → mmap load →
evaluate). Every defect below was reproduced, not inferred.

---

## Verdict

**The shape of the engine is right. The current implementation is not yet a
correct decision-table engine, and it cannot reach PRD scale without changing
how values are encoded and how indexes are stored.**

Three separate statements, because they have different fixes:

1. **The architecture is sound.** Compile-to-artifact, columnar storage,
   dictionary encoding, bitmap candidate filtering, verify-in-priority-order,
   immutable loaded ruleset — this is how a large decision-table engine is
   built. Indexed evaluation measurably works: at 400K rows it is **0.02 ms vs
   5.88 ms** for the linear scan, a ~300x reduction on the same data.
2. **Evaluation semantics are broken in ways that produce wrong answers
   silently.** Priority ordering selects the *lowest*-priority matching rule.
   Ordering comparisons on `DECIMAL` and `STRING` never match. A missing input
   field is treated as the value `0`/empty and can satisfy conditions. These
   are not edge cases; they are the everyday semantics of a decision table.
3. **The memory model does not scale to the PRD numbers.** Heap grows linearly
   with row count in two places that are supposed to be off-heap (the string
   dictionary and the indexes), and per-evaluation allocation grows linearly
   with table size. Measured, then extrapolated: ~600 MB of dictionary and
   ~18 GB of index heap for the 5M-row typical workload, against a 1 GB budget.

Nothing here is unfixable, and none of it invalidates the approach. The fixes
are listed in priority order at the end.

---

## 1. What the design gets right

Worth stating plainly, because the defects below are all local:

- **Lifecycle separation** (validate → compile → load → evaluate) puts the cost
  where the PRD wants it. Evaluation never touches CSV.
- **Columnar + dictionary encoding** is the correct storage choice. No per-row
  or per-cell objects exist at evaluation time; decoders read through absolute
  `ByteBuffer.getInt(int)` offsets, which is both allocation-free and genuinely
  safe for concurrent readers (absolute reads don't touch buffer position).
- **Bitmap candidate filtering then exhaustive verification** is the right
  two-phase pattern: the index can be conservative (blank/no-condition rows are
  correctly unioned into every candidate set) without affecting correctness,
  because verification is exhaustive. `ColumnarBulkKernelParityTest` pins that
  invariant, which is the right thing to pin.
- **The blank-cell rule is handled consistently** — `hasCondition(row) == false`
  returns `true` from every decoder, and each index tracks `noConditionRows`
  separately and ORs it in. This is the subtle part of decision tables and it
  is correct.
- **`ColumnarBulkKernel` is a better engine than the public path**: reusable
  scratch bitmap, selectivity-ordered intersection, early stop. Its design
  instincts are the ones the single-eval path needs.
- **Memory-mapped load really is off-heap for column data.** `LazyDecoderTest`
  and the probe both confirm column bytes stay in the mapping.

---

## 2. Blocking correctness defects

### 2.1 Priority ordering is inverted — the lowest-priority rule wins

`CsvRulesetCompiler.doCompile` physically reorders rows into priority order
(`orderedRows`) **and** writes the source-index permutation into the rule-order
section. `BinaryArtifactReader.readRuleOrder` reads that permutation back, and
`LoadedRulesetImpl.evaluateIndexed`/`evaluateLinear` use it as physical row
indices. The permutation is applied twice.

Reproduced — three rules, all matching, priorities 1 / 50 / 99:

```text
RULE_ID,PRIORITY,REGION,DISCOUNT
RULE_ID,PRIORITY,EQ,SET
LOW,1,APAC,0.01
MID,50,APAC,0.05
HIGH,99,APAC,0.09
```

`evaluate({REGION: APAC})` with `RuleSelectionPolicy.PRIORITY` returns **`LOW`**.
Expected `HIGH`.

The existing `selectsHighestPriorityRule` test passes only because its fixture's
inputs make exactly one rule eligible, so ordering is never exercised.

`FIRST_MATCH` is unaffected (the stored order is the identity permutation there),
which is why this survived.

Fix: apply the permutation once. Either write `0..n-1` into the rule-order
section when rows are physically reordered, or stop reordering rows and keep the
permutation as indirection. Both are consistent with `buildOutput(rowIndex)` and
with the index bitmaps, which are built over physical positions.

### 2.2 Ordering comparisons on DECIMAL, STRING and TIMESTAMP are meaningless

`TypeCoercion.toComparableInt` maps `DECIMAL`, `STRING` and `TIMESTAMP` to
**dictionary IDs**, which are assigned in first-seen order. The scalar decoder
then applies `GT/GTE/LT/LTE` — and `RangeColumnDecoder` applies `BETWEEN_*` — to
those IDs. Comparing insertion-order IDs is not comparing values.

Reproduced, rule `AMOUNT GT 100.00` on a `DECIMAL` column:

| input | expected | actual |
|---|---|---|
| `5.00` | no match | no match (accidentally right) |
| `1000.00` | **match** | **no match** |
| `100.00` | no match | no match |

Rule `NAME GT MMM` on a `STRING` column: `ZZZ` → no match, `AAA` → no match.
Ordering predicates on these types match *nothing*, because an input value that
isn't already in the dictionary resolves to `NULL_ID = 0`.

`DECIMAL` equality is string identity, so scale matters:

| input | rule `AMOUNT EQ 0.5` |
|---|---|
| `BigDecimal("0.5")` | match |
| `BigDecimal("0.50")` | **no match** |
| `0.5d` | match |

`INTEGER` narrows through `Number.intValue()`, so `4294967297L` (2³²+1) matches
a rule written for `1`.

The root cause is that every column type is squeezed into one 4-byte
order-insensitive code. Fix in §7.2.

### 2.3 A missing input field is treated as a real value

`toComparableInt(null, ...)` returns `0`, and every decoder compares that `0` as
if the caller had supplied it. There is no "unknown" state.

Reproduced:

| rule | input | result |
|---|---|---|
| `AGE GT -5` | `{}` (AGE absent) | **matches** |
| `COUNT EQ 0` and `ACTIVE EQ FALSE` | `{}` | **matches** |
| `REGION NOT IN (APAC,EMEA)` | `{}` | **matches** |

`COUNT EQ 0` / `ACTIVE EQ FALSE` matching an empty input is the dangerous one:
those are ordinary cells in real tables, and the engine will fire them for
payloads that simply omit the field.

An unknown *string* is also indistinguishable from a missing one — both are
`NULL_ID` — so `REGION NE APAC` fires for a missing REGION exactly as it does
for `REGION=LATAM`.

A decision table needs an explicit three-valued answer: a non-blank condition
against an absent input should fail (or raise), never silently succeed.

### 2.4 One unmatched variant aborts an entire bulk batch

`LoadedRulesetImpl.evaluateBulk` loops over `evaluate(merged)`, which throws
`EvaluationException` on no-match. The exception propagates out of the whole
call, so 9,999 successful variants are discarded because variant #7,412 matched
nothing. PRD requirement 6 asks for "one independent decision result per
variant."

`ColumnarBulkKernel` already does the right thing (`null` for no match, batch
continues) — the public path just doesn't use it. Related: throwing a
stack-filling exception per unmatched input is also the wrong cost model for a
10K-variant batch.

---

## 3. Scale and memory: measured, then extrapolated

All numbers from a real compile → `writeTo` → `load(Path, memoryMap())` cycle.
Table: N rows, 10 `EQ` string input columns at 500 distinct values each, unique
`RULE_ID` per row.

| | 200K rows | 400K rows | scaling |
|---|---|---|---|
| Heap after mmap load, indexes **off** | 24.1 MB | 47.9 MB | linear in rows |
| Heap after mmap load, indexes **on** | 144.3 MB | 291.1 MB | linear in rows |
| ⇒ index heap alone | 120.2 MB | 243.2 MB | linear |
| Allocation per single evaluation | 74.0 KB | 147.3 KB | **linear in rows** |

Every line doubles when the row count doubles. That is the finding: three
quantities that must be independent of table size (or off-heap) are neither.

### 3.1 The string dictionary is on-heap and grows with row count

`StringDictionaryReader` materializes a `String[]` plus a
`HashMap<String,Integer>` of every distinct value — and `RULE_ID` is a `STRING`
column with a unique value per row, so the dictionary has ≥ N entries by
construction. Measured ~120 bytes/row.

- 5M rows → **~600 MB heap** before a single index is built
- 20M rows → **~2.4 GB heap**

The PRD budget is 1 GB total. `DECIMAL` and `TIMESTAMP` values also live here,
so any table with high-cardinality decimals compounds it.

### 3.2 Index bitmaps are uncompressed and one-per-distinct-value

`EqualityIndex`, `ComparisonIndex` and `SetMembershipIndex` each allocate a full
`long[ceil(rows/64)]` **per distinct value**, on heap. Cost per column is
`distinct × rows / 8` bytes, independent of how sparse each value is.

At PRD typical (5M rows, 60 input columns, 500 distinct values/column):

```
60 columns × 500 values × (5,000,000 / 8) bytes = 18.75 GB
```

The javadoc on `EqualityIndex` claims "~1 MB for 5M rows with 1000 unique
values (1000 bitmaps * 625 KB / 64 shared structure)". There is no shared
structure and no division by 64 — 1000 × 625 KB is 625 MB, for one column. The
comment should be deleted; it is what makes the design look affordable.

`memorySizeBytes()` computes the real figure correctly, but nothing consults it,
so nothing bounds index construction.

### 3.3 Per-evaluation working set scales with the table

`evaluateIndexed` calls `CandidateBitmap.copy(allRowsBitmap)` on every
evaluation, then `EqualityIndex.getCandidates` returns *another* freshly
allocated bitmap per column. Measured 74 KB/eval at 200K rows; at 5M rows that
is ~1.9 MB per evaluation.

- 200 concurrent evaluations → ~370 MB of churn per round
- 10,000-variant bulk batch → ~19 GB of garbage

This directly contradicts PRD NFR 3 ("per-evaluation runtime memory must be
bounded and must not scale with total table size"). `ColumnarBulkKernel`'s
per-thread reusable scratch is the right pattern; single-eval needs it too.

### 3.4 Candidate iteration is O(total rows), not O(candidates)

After intersecting down to (typically) a handful of candidates, both
`evaluateIndexed` and the bulk kernel do:

```java
for (int rowIndex : ruleOrder) {          // all 5M entries
    if (CandidateBitmap.isSet(candidates, rowIndex) && ...) 
```

The index narrows the *verification* work but not the *iteration* work. This is
why latency still tracks row count:

| table | matching single eval, median | p95 |
|---|---|---|
| 200K rows | 45 µs | 151 µs |
| 1M rows | 160 µs | 259 µs |

Extrapolating, 5M rows lands around 0.8–1.5 ms for this shape — still inside the
250 ms p95 target, so **latency is the one PRD target the current design
plausibly meets**. But the scan is pure waste: iterating set bits by word
(`Long.numberOfTrailingZeros`) makes it O(candidates). Note this also means the
early-exit path is misleadingly fast — an input that matches *nothing* exits
during intersection and never pays the scan, which is what the current
throughput numbers mostly measure.

Related, in the bulk kernel: `CandidateBitmap.cardinality(scratch)` is a full
popcount over the whole bitmap after *every* column intersection — 60 full
passes over 625 KB per input at PRD scale. Track cardinality incrementally or
use a thresholded early-exit count.

### 3.5 `ComparisonIndex` lookup cost is O(distinct values)

`getCandidates` ORs together one full bitmap per distinct threshold on the
matching side of the input. For a column with 10,000 thresholds over 5M rows
that is ~10,000 × 78,125 words of OR per column per evaluation. Precompute
**prefix-OR (cumulative) bitmaps** at build time and the lookup becomes a single
binary search plus one bitmap reference — O(log n) instead of O(n × words).

### 3.6 The artifact format has a hard 2 GB ceiling

`BinaryArtifactWriter.write` returns `byte[]`, `CompiledRuleset.bytes()` is
`byte[]`, and every offset in `BinaryArtifactReader` (`int base = dataOffset +
col.dataOffset()`) is a 32-bit int. Scalar column data is `rows × 4` bytes:

- 5M rows × 82 columns ≈ **1.6 GB** — under the ceiling, but only just, and it
  must exist as a single contiguous heap array at compile time
- 20M rows × 170 columns ≈ **13.6 GB** — cannot be represented at all

The PRD's stated maximum (20M rows, 120 inputs, 50 outputs) is unreachable by
the format, not just by the machine.

### 3.7 Compilation is not streaming, despite the docs

`docs/architecture.md` states "CSV is streamed and never fully loaded into
memory." `CsvRulesetCompiler.doCompile` does:

```java
List<String[]> allRows = new ArrayList<>();
while ((dataRow = reader.readNext()) != null) { allRows.add(dataRow); }
```

and then holds, simultaneously: `allRows`, `orderedRows`, a full second copy in
`projectedRows` (`encodeRuleData` re-projects every row into a new `String[]`),
the `ByteArrayOutputStream` of all column data, and the final artifact array.
Peak heap is roughly 3–4× the whole table as Java `String`s. `StreamingCsvRowReader`
is a genuine streaming reader; the compiler just doesn't use it as one.

Also: `buildRuleOrder` sorts a `List<Integer>` with a comparator that calls
`Integer.parseInt` inside the comparison — ~115M parses and full boxing at 5M
rows. Extract the priority key array once, then sort indices.

---

## 4. Coverage gaps against the PRD

| PRD requirement | Status |
|---|---|
| FR1 decision tables (CSV/JSON/DB) | CSV only; JSON and DB sources absent |
| FR2 test cells, dual artifacts | Implemented and tested |
| FR3 compile to execution form | Implemented |
| FR4 full-table load | Implemented (mmap and on-heap) |
| FR5 indexed evaluation | EQ, GT/GTE/LT/LTE, IN/NOT_IN. **`NE`, `BETWEEN_*`, `NOT_BETWEEN_*` unindexed** — a table keyed on `BETWEEN` (the documented headline example) gets no narrowing at all and falls back to full verification |
| FR6 bulk mode | Present but aborts the batch on any unmatched variant (§2.4); no parallelism on the public path |
| FR7 determinism & isolation | Deterministic; isolation is fine (no shared mutable state) |
| FR8 immutable, thread-safe | Correct — final fields, absolute buffer reads, defensive copies |
| FR9 explicit lifecycle | Implemented |
| NFR1 20M rows | Not representable (§3.6) |
| NFR2 <1 GB heap | Not met — ~600 MB dictionary + ~18 GB indexes at 5M rows |
| NFR3 bounded per-eval memory | Not met — scales linearly with rows (§3.3) |
| NFR4/5 parallel & bulk throughput | Plausible once §3.3 is fixed; GC churn is the current risk |
| NFR7 p95 < 250 ms | **Plausibly met** even today |

Two validation gaps also matter for a rule engine specifically:

- `CsvRulesetValidator.validateCellValue` never checks cell values against the
  schema's `ColumnType` — it only checks parenthesis shape. A non-numeric cell
  in an `INTEGER` column is caught (if at all) as an exception during
  compilation, not as a validation issue with row/column context.
- There is no duplicate-`RULE_ID` check, and no overlap/coverage analysis.
  Overlap and gap detection is what makes a decision table maintainable at
  100K+ rows; without it, §2.1-style ordering bugs are invisible to authors.

---

## 5. Documentation that overstates the code

These matter because they are load-bearing for design decisions:

- `architecture.md`: "CSV is streamed and never fully loaded into memory" — it is
  fully loaded (§3.7).
- `EqualityIndex` / `SetMembershipIndex` javadoc: "~1 MB for 5M rows with 1000
  unique values" — off by ~600× (§3.2).
- `architecture.md`: "Indexes and rule data stored off-heap when possible" —
  rule data is; indexes are entirely on-heap and never persisted to the artifact.
- `architecture.md`: "Surface lightweight counters for index hit rate and scan
  fallbacks" — no such counters exist.
- CI (`.github/workflows`) runs `mvn package` only, so the `scale` and `memory`
  tags never execute. Every scale claim in the docs is currently unverified by
  automation.

---

## 6. Smaller notes

- `LoadedRulesetImpl.close()` closes the `FileChannel` but leaves the mapping
  reachable through the decoders. Concurrent evaluation during `close()` is safe
  today only because unmapping is left to the GC. Worth documenting as a
  contract ("no evaluation may be in flight or started after close").
- `SetColumnEncoder` writes list lengths as 16-bit, capping an `IN` set at
  65,535 members. Fine, but it should be validated and reported, not truncated.
- `evaluate` throwing on no-match forces callers into exception-driven control
  flow. `Optional<DecisionOutput>` or a `NO_MATCH` sentinel would be cheaper and
  more honest, and would fix §2.4 for free.
- `DATE` accepts a bare `Integer` as epoch-days from callers, which is an easy
  way to feed a year (`2026`) in as 1975-06-19. Require `LocalDate`.
- `BinaryArtifactReader.columnDataSize` derives an `IN` column's packed length by
  scanning all `rowCount` offsets/lengths at load time, per set column. Store the
  length in the column definition instead.

---

## 7. Recommended fix order

**Tier 1 — correctness (the engine gives wrong answers without these)**

1. **Rule-order double permutation** (§2.1). One-line class of fix; write the
   identity permutation when rows are physically reordered. Add a test where the
   highest-priority rule appears last in the file *and* a lower-priority rule
   also matches.
2. **Order-preserving value encoding** (§2.2). Assign dictionary IDs in **sorted
   value order** so `STRING` comparisons are lexicographic; for `DECIMAL` store a
   scaled integer (fixed scale per column, from the schema) and for `TIMESTAMP`
   store epoch millis. Widen value slots to 8 bytes, or add a per-column codec so
   only wide columns pay. For an input value not present in the dictionary, an
   ordering predicate needs the *insertion rank* (binary search over the sorted
   values), not `NULL_ID`.
3. **Explicit missing/unknown semantics** (§2.3). Thread a `present` flag
   alongside the coerced code; a non-blank condition against an absent input must
   fail (make raise-vs-fail a `LoadOptions`/`CompileOptions` choice). Add a test
   matrix over every operator × {absent, unknown-value, present}.
4. **Per-variant bulk results** (§2.4). Return a no-match result per variant
   rather than throwing out of the batch.

**Tier 2 — scale (these decide whether the PRD numbers are reachable)**

5. **Compress and persist the indexes** (§3.2). Roaring bitmaps (or delta-encoded
   posting lists) instead of dense `long[]` per value, built at *compile* time
   into the artifact and read through the mapping — not rebuilt on heap at load.
   Enforce a budget using the `memorySizeBytes()` that already exists, and index
   selectively (skip low-selectivity columns) rather than every eligible column.
6. **Get `RULE_ID` out of the heap dictionary** (§3.1). Store it as an offset+
   length blob in the artifact and decode on demand — it is only ever needed for
   the one winning row. Same for high-cardinality `DECIMAL`/`TIMESTAMP` columns.
7. **Bound the per-evaluation working set** (§3.3). Reuse a thread-local scratch
   bitmap (copy the `ColumnarBulkKernel` pattern), and have `getCandidates`
   intersect *into* the caller's buffer instead of allocating a new one.
8. **Iterate set bits, not all rows** (§3.4), and track cardinality incrementally.
9. **Prefix-OR bitmaps for `ComparisonIndex`** (§3.5) — turns an O(distinct)
   lookup into O(log distinct).
10. **64-bit artifact offsets and a streaming artifact writer** (§3.6), plus a
    genuinely streaming two-pass compiler (§3.7) so compilation memory is
    independent of row count.

**Tier 3 — completeness**

11. Index `BETWEEN_*` (interval/segment index) and `NE`; today the documented
    headline example gets no index narrowing.
12. Schema-type validation of cell values, duplicate-`RULE_ID` detection, and
    overlap/gap analysis in the validator.
13. Run the `scale` and `memory` tags in CI (nightly, smaller row counts) so the
    scale claims are continuously checked rather than asserted in prose.

---

## Reproducing the findings

Probes were plain `main()` classes compiled against
`kisoku-api-0.1.0-SNAPSHOT.jar` and `kisoku-runtime-0.1.0-SNAPSHOT.jar` on the
classpath (classpath mode bypasses JPMS; ServiceLoader resolves via
`META-INF/services`), exercising only the public API:

```
Kisoku.compiler().compile(...) → compiled.writeTo(path)
→ Kisoku.loader().load(path, LoadOptions.memoryMap()) → ruleset.evaluate(...)
```

Memory figures use `Runtime.totalMemory() - freeMemory()` after three
GC+settle cycles; per-evaluation allocation uses
`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, measured over 200
evaluations after a 50-evaluation warmup.
