# ADR-0010: Dual Execution Model — Low-Latency Single-Eval and High-Throughput Vectorized Bulk

## Status

Proposed

## Context

Two independent "size" axes had been conflated in the PRD and the scale tests:

1. **Rule-table size** — the decision table itself. In real deployments this is *tens of
   thousands* of rows with 60–120 input and 20–50 output columns. A 50k-row table compiles
   to ~30 MB and is comfortable on-heap; it is *not* the 5M–20M figure the scale tests stress.
2. **Input volume** — the data scored *against* the table: millions of independent inputs,
   evaluated in parallel.

These demand opposite optimizations, and the engine must serve both from one immutable ruleset.
The input arrives from a heterogeneous set of sources spanning two access patterns:

- **Latency-bound, synchronous, push:** REST request, single DB lookup. One (or a few) inputs,
  200–500 concurrent, answer required immediately (p95 < 250 ms).
- **Throughput-bound, asynchronous, pull:** Parquet/CSV/JSON files, Kafka/SQS streams, paged DB
  reads, protobuf. Millions of inputs, consumed at the engine's pace, latency-tolerant.

The target for the throughput path is **millions of evaluations/sec per pod**. A cycle budget makes
the constraint concrete. At 1M evals/sec on 4 vCPUs (~12e9 cycles/sec) the budget is **~12,000
cycles per evaluation**. The current indexed single-eval kernel, for a 50k-rule, ~100-column table,
costs on the order of **80,000+ cycles** per input — roughly **10–20× over budget**:

- per indexed column: `map.get(name)` (hash) + `dictionary.getId` (hash) + `index.getCandidates`
  + a bitmap `AND` over `ceil(50000/64) = 782` longs;
- ~100 columns ⇒ ~78,000 word-ops for intersection alone, plus ~200 hash lookups.

Parallelism multiplies throughput by core count (4–8×); it cannot close a 10–20× *per-eval* gap.
The row-at-a-time `evaluate(DecisionInput)` API — a `Map<String,Object>` per input plus per-cell
hashing — is structurally incapable of the throughput target regardless of threading.

Deployment is containerized (Docker on ECS/K8s). Compilation runs in-process (typically at
startup/reload), so its transient memory spike shares the pod's cgroup limit; at the real table
sizes (tens of thousands of rows) that spike is a few hundred MB and is not the binding constraint.

## Decision

Adopt a **dual execution model**: one immutable ruleset artifact, two evaluation paths, selected by
access pattern — never one kernel forced to serve both.

### Path 1 — Single-eval (latency)

The existing `LoadedRuleset.evaluate(DecisionInput)` path, unchanged. Indexed, immutable,
thread-safe (concurrent evaluation proven, see `FileBackedLoadTest`). A single indexed eval is
sub-millisecond of compute, so the 250 ms p95 is dominated by network, serialization, and GC — the
engine has large headroom. REST and single DB lookups are thin adapters: HTTP/record →
`DecisionInput.of(map)` → `evaluate` → response. No API change; no flyweight needed at this scale.

### Path 2 — Vectorized columnar bulk kernel (throughput)

A separate kernel, additive to the engine, built on the same immutable ruleset (indexes +
absolute-read decoders from ADR-0003/WS3). Two levers cut the per-eval cost; SIMD shaves the
remainder; both are *multiplicative* and neither is "more cores":

**Lever 1 — Columnar input (kills the coercion tax).** `InputSource` adapters produce a
`RecordBatch`: one primitive `int[]` of pre-coerced codes per input column (B rows) plus a null
mask. String columns are dictionary-encoded against the ruleset dictionary *once per batch-column*,
not per cell. No `Map` per input, no boxing. B sized for L2 residency (~1k–8k rows).

**Lever 2 — Selectivity-pruned intersection (kills the all-columns AND tax).** Precompute per-column
selectivity (cardinality) at load and an intersection order. Per input: walk only the 2–3 most
selective indexed columns, `AND` each candidate bitmap into a reusable per-thread scratch bitmap,
early-exit when the candidate set is small, then verify the handful of survivors directly against
the remaining columns and pick highest priority. Cost collapses from ~78,000 to ~hundreds of
word-ops.

**SIMD (Java Vector API).** The scratch-bitmap `AND` is the vectorization target (`LongVector` ANDs
4–8 longs/instruction). **Staged: ship a scalar kernel first** (columnar input + pruning +
parallelism already likely hits target on selective tables), add SIMD as a drop-in second step —
the Vector API is preview on JDK 21 and carries the same `--enable-preview` friction that steered
ADR-0003 away from FFM.

**Parallelism is caller-owned.** The engine exposes batches as a pull-based `Spliterator`/`Stream`
and accepts a *caller-supplied* `Executor`; it never spins its own thread pool. In a CPU-limited
container the platform must own scheduling. Workers share the immutable ruleset lock-free (absolute
reads) and scale near-linearly with cores.

**Output is a sink.** Results stream to an `OutputSink` (file/Kafka/DB) in columnar batches; the
full input/output volume is never materialized. Memory is **batch-bounded**: peak ≈ B × columns ×
few bytes + (scratch bitmaps × threads), independent of total volume.

### The adapter funnel

N input formats × 1 kernel. Every adapter's only job is "native record → engine representation"
(columnar `RecordBatch` for bulk, `DecisionInput` for single). The evaluator never imports Parquet
or protobuf. This is the same SPI discipline as the lifecycle components (ADR-0001) and the
`DecisionTableSource` port, lifted one level to the input side. Bounded, synchronous work (REST)
funnels to Path 1; unbounded volume funnels to Path 2. A REST call may *trigger* a bulk job (return
a job handle) but is never the bulk transport — millions of rows are not POSTed synchronously.

### Deployment topology

The throughput kernel is core-greedy and will starve latency-sensitive traffic if co-resident.
Therefore **latency and throughput run as separate tiers off one image**: REST-serving pods
(autoscaled on RPS/latency) and batch-scoring workers (autoscaled on queue/lag), each loading the
**same compiled `.kss` artifact** via the memory-mapped `load(Path)` handoff (ADR-0003/WS3). This —
not large table size — is the real justification for the file-artifact + mmap work: one artifact,
compiled once, loaded identically across heterogeneous deployments.

## Alternatives Considered

| Alternative | Pros | Cons | Why Rejected |
|-------------|------|------|--------------|
| **Single unified kernel for both paths** | One code path to maintain | Latency path needs low per-call overhead; throughput path needs columnar/vectorized batch. One kernel optimizes neither. | Conflicting constraints; a `Map`-based call cannot hit millions/sec, and a columnar batch is wrong for a single REST request |
| **Row-at-a-time bulk + parallelism only** | Minimal change; reuse `evaluate` | Per-eval cost is 10–20× over budget; threading multiplies by core count only | Cannot reach millions/sec without cutting per-eval cost; would need 100+ cores |
| **Engine-owned thread pool** | Simple for callers | Fights cgroup CPU accounting; competes with the host service's pools and the latency tier | Caller-owned executor is mandatory under K8s CPU limits |
| **RETE / Phreak (Drools-style)** | Mature; great for interacting rules + chaining | Caches partial matches across a *stateful* working memory; pure overhead for independent stateless inputs; decision-table row→rule explosion | Wrong tool for high-volume stateless scoring of independent inputs |
| **Full SIMD from day one** | Maximum throughput immediately | Vector API is preview on JDK 21 (`--enable-preview`); higher risk before the scalar baseline is measured | Scalar-first likely meets target on selective tables; add SIMD only where measurement demands |
| **Co-resident latency + throughput in one pod** | Fewer deployments | Throughput kernel saturates cores and blows latency p95 | Two tiers off one image isolates the contention cleanly |

## Performance Envelope

"Max throughput" is a **function of table selectivity**, not a flat SLA — and documenting it as a
curve is what keeps it honest:

| Table shape | Per-input cost | Indicative throughput (4 vCPU) |
|-------------|----------------|--------------------------------|
| Selective (a few high-cardinality discriminating columns) | low thousands of cycles | ~1–3M/sec, scaling with cores |
| Mixed | moderate | hundreds of thousands–~1M/sec |
| Low-selectivity (≈100 equally-weak columns, heavy rule overlap) | approaches full-intersection cost | hundreds of thousands/sec at best |

No kernel escapes the low-selectivity case; it is intrinsic to the table shape. The binding cost
driver at this throughput is **selectivity, not row count**.

## Consequences

### Positive

- **Each path is optimal for its access pattern**: single-eval keeps sub-ms latency headroom;
  bulk reaches the throughput target on selective tables.
- **Reuses existing work**: bitmap indexes (ADR-0005/0009) become the pruning pre-filter;
  absolute-read decoders (WS3) are the lock-free verify path; the immutable ruleset is the shared
  read-only structure; the mmap artifact handoff is the multi-tier load substrate.
- **Bounded memory** on the throughput path: batch-bounded, independent of total input volume.
- **Composability**: `InputSource`/`OutputSink` is also the substrate for ruleset chaining (one
  table's output sink feeds the next table's input source) — to be detailed in a future ADR.

### Negative

- **Two kernels to keep semantically identical.** Mitigation: a parity oracle — bulk results must
  equal single-eval results for the same inputs (extends the existing indexed-vs-linear parity
  check).
- **Vector API is preview on JDK 21.** Mitigation: scalar-first; SIMD as an isolated, optional
  acceleration of the `AND` loop.
- **Per-source columnar adapters** are more upfront work than a generic `Map` path, but are the
  only road to the target; the funnel keeps it N adapters → 1 kernel.

### Neutral

- **String coercion** remains O(cells) hash lookups at ingestion; source-side dictionary alignment
  (map a source's native dictionary → the ruleset dictionary) is a further win, deferred.
- **`DecisionInput` is bypassed** on the bulk path (pure columnar) and retained for single-eval; no
  forced API change.
- **Compile-time memory** (the in-process startup spike) is a minor optimization at real table
  sizes; streaming the compiler to int-columns is deferred unless table sizes grow.

## References

- ADR-0001: Multi-Module API/Runtime Split (SPI/adapter discipline, lifecycle ports)
- ADR-0003: Direct/Memory-Mapped Buffer Strategy (off-heap load, the `.kss` artifact handoff)
- ADR-0005: Bitmap-Based Indexing (CandidateBitmap, the pruning pre-filter)
- ADR-0009: Set Membership Index Strategy (index pattern reused by the bulk kernel)
- `LoadedRuleset.evaluate` / `evaluateBulk`: current single and (serial) bulk entry points
- `DecisionTableSource` / `DecisionTableSources`: the existing source port to mirror for inputs
