# Kisoku Engine Review: Does this design work as a rule engine?

Scope: full read of `kisoku-api` and `kisoku-runtime` at commit `dd9257f`, plus
executable probes run against the built jars (compile → write → mmap load →
evaluate). Every defect below was reproduced, not inferred.

> **Status.** All Tier 1 defects (§2.1–§2.6) have since been fixed on this
> branch, each with a regression test; §7 marks them off and §8 records what the
> fixes changed. §2.1, §2.5 and §2.6 were revised after the first fix round:
> §2.1's original write-up assumed a higher priority number meant a higher
> priority, which is backwards, and §2.5/§2.6 surfaced while verifying the
> corrected ordering. The Tier 2 scale findings (§3) are unchanged and still open,
> with one partial improvement noted in §8. Sections 1–6 describe the code as
> reviewed, so the reproductions stay readable against the commit they were run
> against.

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

### 2.1 Priority selection is broken twice over

`PRIORITY` counts up from the most important rule: 1 outranks 2, which outranks
3. `docs/api.md` says so — "Lower numeric value = higher priority" — and the
implementation contradicts it in two independent ways that partly mask each
other.

**Wrong direction.** `CsvRulesetCompiler.buildRuleOrder` sorts
`.reversed()`, commented "Descending order (higher priority first)". That
selects the *least* important matching rule.

**Ordering applied twice.** `doCompile` physically reorders rows into priority
order (`orderedRows`) **and** writes the source-index permutation into the
rule-order section. `BinaryArtifactReader.readRuleOrder` reads that permutation
back, and the evaluation paths use it as physical row indices — so the ordering
is applied twice and evaluation order is scrambled whenever priority order
differs from source order.

Reproduced — four rules, all matching, in a file ordered 99 / 50 / 2 / 1:

```text
RULE_ID,PRIORITY,REGION,DISCOUNT
RULE_ID,PRIORITY,EQ,SET
P99,99,APAC,0.99
P50,50,APAC,0.50
P02,2,APAC,0.02
P01,1,APAC,0.01
```

`evaluate({REGION: APAC})` must return `P01`. It does not.

The existing `selectsHighestPriorityRule` test passes only because its fixture's
inputs leave one rule eligible on the path that matters, so neither defect is
exercised.

`FIRST_MATCH` is unaffected by the permutation bug (the stored order is the
identity permutation there), which is part of why this survived.

Fix: sort ascending, and apply the permutation once — write `0..n-1` into the
rule-order section, since rows are already physically reordered. Both are
consistent with `buildOutput(rowIndex)` and with the index bitmaps, which are
built over physical positions. While there: a blank priority currently sorts as
0, which under the correct direction would make an unnumbered rule outrank every
numbered one; it should sort last.

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

### 2.5 A zero-valued output decodes as "no value"

`TypeCoercion.decodeValue` short-circuits on the null sentinel code before
dispatching on type. Several types encode an ordinary value to that same code:
decimal zero, `1970-01-01`, the epoch instant, `false`. Such a cell decodes to
`null` even though its presence bit is set.

Presence is already the presence bitmap's job — `ScalarColumnDecoder.getValue`
checks `hasCondition(row)` before decoding — so the sentinel check inside
`decodeValue` is both redundant and wrong. It should be removed.

Under the format as reviewed this hits `DATE` (`1970-01-01`) and `BOOLEAN`
partially; it widens to `DECIMAL` and `TIMESTAMP` once those are stored
numerically (§7.2).

### 2.6 A blank output cell throws

`LoadedRulesetImpl.buildOutput` puts `decoder.getValue(rowIndex)` into the
outputs map unconditionally, and `DecisionOutput` copies that map with
`Map.copyOf`, which rejects null values. A blank output cell therefore fails
evaluation with a `NullPointerException` from inside `Map.copyOf` — no message,
no column name.

Blank output cells are legal: the CSV rules require only that *at least one*
output per row is non-blank, and `docs/api.md` already specifies the intended
behaviour — "Output cell is blank → output key omitted from
`DecisionOutput.outputs()`". The fix is to omit the key.

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

**Tier 1 — correctness (the engine gives wrong answers without these)** — *done*

1. ~~**Priority selection**~~ (§2.1). **Fixed** — sort ascending, store the
   identity permutation, rank blank priorities last. One-line class of fix; write the
   identity permutation when rows are physically reordered. Add a test where the
   highest-priority rule appears last in the file *and* a lower-priority rule
   also matches.
2. ~~**Order-preserving value encoding**~~ (§2.2). **Fixed.** Assign dictionary IDs in **sorted
   value order** so `STRING` comparisons are lexicographic; for `DECIMAL` store a
   scaled integer (fixed scale per column, from the schema) and for `TIMESTAMP`
   store epoch millis. Widen value slots to 8 bytes, or add a per-column codec so
   only wide columns pay. For an input value not present in the dictionary, an
   ordering predicate needs the *insertion rank* (binary search over the sorted
   values), not `NULL_ID`.
3. ~~**Explicit missing/unknown semantics**~~ (§2.3). **Fixed.** Thread a `present` flag
   alongside the coerced code; a non-blank condition against an absent input must
   fail (make raise-vs-fail a `LoadOptions`/`CompileOptions` choice). Add a test
   matrix over every operator × {absent, unknown-value, present}.
4. ~~**Per-variant bulk results**~~ (§2.4). **Fixed.**
5. ~~**Zero-valued outputs and blank output cells**~~ (§2.5, §2.6). **Fixed** —
   decoding no longer reads the null sentinel as absence, and a blank output
   cell omits its key instead of throwing. Return a no-match result per variant
   rather than throwing out of the batch.

**Tier 2 — scale (these decide whether the PRD numbers are reachable)**

5. ~~**Compress the indexes**~~ (§3.2). **Fixed** — posting-list (CSR) indexes in
   direct buffers, one entry per present cell, off the heap (ADR-0011). The
   *persist into the artifact at compile time* half remains open; indexes are
   still built at load time.
6. ~~**Get `RULE_ID` out of the heap dictionary**~~ (§3.1). **Fixed** — artifact
   format 3.0 stores `RULE_ID` as inline UTF-8 (offsets + blob) decoded on demand
   for the winning row; `DECIMAL`/`TIMESTAMP` left the dictionary in 2.0. The
   rule-order identity permutation is also no longer materialized. Load-time heap
   is now constant in row count (§10).
7. ~~**Bound the per-evaluation working set**~~ (§3.3). **Fixed** — the
   bitmap-intersection pipeline is gone; evaluation allocates two small per-call
   arrays sized by column count, constant in rows (measured 1.0 KB/eval at both
   200K and 400K rows, down from 74/147 KB).
8. ~~**Iterate set bits, not all rows**~~ (§3.4). **Fixed** — candidates are
   enumerated from the driver column's postings slice directly; matching-eval
   latency is flat across table sizes (12 µs at 200K and at 1M rows).
9. ~~**Prefix-OR bitmaps for `ComparisonIndex`**~~ (§3.5). **Fixed differently** —
   comparison lookups are an O(log distinct) offset subtraction on the CSR
   layout; no per-threshold bitmaps exist to OR.
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


---

## 8. What the Tier 1 fixes changed

Recorded here so the measurements above stay comparable. Same probes, rebuilt
jars.

**Behaviour.** Every reproduction in §2 now gives the right answer:

| Case | Before | After |
|---|---|---|
| Priorities 99/50/2/1 in that file order, all matching (§2.1) | `P99` | `P01` |
| Blank priority vs priority 7 (§2.1) | blank wins | numbered wins |
| `AMOUNT=1000.00` vs `AMOUNT GT 100.00` (§2.2) | no match | matches |
| `BigDecimal("0.50")` vs `AMOUNT EQ 0.5` (§2.2) | no match | matches |
| `NAME=ZZZ` vs `NAME GT MMM` (§2.2) | no match | matches |
| `4294967297L` vs `ACCOUNT EQ 1` (§2.2) | matches (truncated) | no match |
| `{}` vs `COUNT EQ 0 AND ACTIVE EQ FALSE` (§2.3) | matches | no match |
| `{}` vs `REGION NOT IN (APAC,EMEA)` (§2.3) | matches | no match |
| `REGION=LATAM` vs `REGION NE APAC` (§2.3) | matches | matches (unchanged — correct) |
| Bulk with one unmatched variant (§2.4) | whole batch throws | that variant empty, rest returned |
| `DISCOUNT` output of `0.00` (§2.5) | decodes as `null` | decodes as `0.00` |
| Blank output cell (§2.6) | `NullPointerException` | key omitted |

**Artifact format 2.0.** Values are now a single 64-bit order-preserving code
per cell instead of a 4-byte dictionary ID or narrowed int, dictionary entries
are written sorted, and column definitions carry a decimal scale (12 → 16
bytes). 1.x artifacts must be recompiled. Two knock-on effects on §3:

- **Artifacts are ~1.8× larger** (12.6 MB → 22.5 MB for the 200K-row probe),
  which brings the 2 GB ceiling of §3.6 closer. The typical workload lands
  around 3 GB rather than 1.6 GB, so §3.6 moves from "under the ceiling, barely"
  to **blocking**, and the 64-bit-offset work is now required rather than
  merely advisable.
- **Dictionary heap roughly halved** — 24.1 MB → 10.8 MB at 200K rows, ~120 to
  ~54 bytes/row. Sorted IDs let the loader resolve strings by binary search over
  the entries it already holds, so the reverse `HashMap<String,Integer>` is
  gone, and `DECIMAL`/`TIMESTAMP` values no longer enter the dictionary at all.
  That takes §3.1's 5M-row projection from ~600 MB to ~270 MB. Still too much,
  and still linear in rows because `RULE_ID` is unique per row — fix 6 stands.

Index heap (§3.2), per-evaluation allocation (§3.3) and the O(rows) candidate
scan (§3.4) are untouched by these fixes and remain the blocking scale issues.

**API.** `DECIMAL` outputs now decode as `BigDecimal` at the column's scale
rather than as a `String`, and `INTEGER` outputs as `Long`. `BulkResult` gained
`result(int)`, `matched(int)`, `size()`, `matchedCount()` and
`unmatchedIndices()`; its `results()` list keeps one entry per variant and holds
`null` where nothing matched. `DATE` inputs must be a `LocalDate` — the old
"bare `Integer` means epoch day" path is gone, since it silently read a year as
a day number.


---

## 9. What the Tier 2 index rework changed

Same probes as §3, rebuilt jars. The dense per-value bitmaps (§3.2) are replaced by
off-heap posting-list (CSR) indexes with driver-based candidate enumeration (ADR-0011);
single-eval and the bulk kernel now share one matcher, so bulk/single parity is structural.

| Metric (200K rows × 10 EQ cols × 500 distinct) | Before (§3) | After |
|---|---|---|
| Index heap | 120.2 MB, linear in rows | **~0.1 MB** (off-heap direct buffers) |
| Heap after mmap load, indexes on | 144.3 MB | 10.8 MB |
| Per-eval allocation | 74.0 KB, linear in rows | **1.0 KB, constant** (also 1.0 KB at 400K) |
| Matching single eval, median | 45 µs @200K → 160 µs @1M | **12 µs, flat** at both sizes |
| Bulk 1000 variants | 66–75 ms | 11–20 ms |
| Linear scan (indexes off) | 5.88 ms/eval | 1.52 ms/eval (inputs coerced once, not per row) |

At PRD-typical row count (5M rows × 10 EQ cols × 500 distinct, mmap load, prewarmed):

- Heap after load: **269 MB** (56.5 bytes/row, dictionary-dominated) — inside the 1 GB budget,
  where the dense indexes alone would have added ~3 GB for this shape and ~18 GB at 60 columns.
- Load + index build: 13.3 s (target: <60 s).
- Worst-case single eval (input matching nothing, so the driver's ~10K candidates are
  exhausted): median 618 µs against the 250 ms p95 target.
- Compile: 84.9 s — over the 60 s target, unchanged by this work; that is the non-streaming
  compiler (§3.7, fix 10).

Consequences for the §4 scorecard:

- **NFR2 (<1 GB heap)**: index heap no longer scales with rows at all; the remaining
  linear-in-rows heap consumer is the string dictionary (§3.1, `RULE_ID` — fix 6, still open).
- **NFR3 (bounded per-eval memory)**: now met — measured constant across table sizes.
- **FR5**: `NE` is now indexed (count-only), leaving only `BETWEEN_*` unindexed.

Still open in Tier 2: compile-time index persistence (load-time build remains), `RULE_ID`
out of the heap dictionary (fix 6), 64-bit artifact offsets + streaming writer (fix 10),
and the streaming compiler (§3.7).


---

## 10. What the dictionary/RULE_ID rework changed

Artifact format 3.0: the `RULE_ID` column is stored as inline UTF-8 (presence bitmap, byte
offsets, blob) instead of dictionary codes, decoded on demand from the (memory-mapped) buffer
for the one winning row of an evaluation. Rule ids are unique per row by design, so this
removes the dictionary's linear-in-rows growth — the dictionary now holds only column names
and distinct STRING input/output values. The rule-order section's identity permutation (4
bytes/row on heap) is also represented implicitly; a non-identity order (a foreign artifact)
is still materialized and honored via the linear-scan path.

| Heap after mmap load (10 EQ cols × 500 distinct) | §3 baseline | after §9 | after §10 |
|---|---|---|---|
| 200K rows | 144.3 MB | 10.8 MB | **0.1 MB** |
| 400K rows | 291.1 MB | 21.5 MB | **0.1 MB** |
| 5M rows | (projected ~1.8 GB) | 269 MB | **0.2 MB** |

Load-time heap is now **constant in table size** — 0.0 bytes/row at 5M rows. The 5M
compile also came in at 58.2 s (under the 60 s target on this shape, versus 84.9 s in §9)
because rule ids as inline bytes are smaller than 8-byte codes plus dictionary entries;
the compiler is still non-streaming, so treat that as borderline, not solved. Combined with §9's constant per-evaluation
allocation, the JVM-heap side of NFR2/NFR3 no longer scales with rows at all for this shape;
the table, indexes, and rule ids all live off-heap (mmap + direct buffers).

Still open in Tier 2: compile-time index persistence, 64-bit artifact offsets + streaming
writer, and the streaming compiler (§3.7) — compile-time heap and the 60 s compile target
remain the outstanding scale problems.
