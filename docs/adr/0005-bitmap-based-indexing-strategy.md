# ADR-0005: Bitmap-Based Indexing Strategy

## Status

Accepted

## Context

Evaluating a decision table against input requires finding all rows where every input column's condition matches. For a 20M row table with 60 input columns, naive linear scan would check 1.2 billion conditions per evaluation.

The PRD requires:
- Single evaluation p95: <250ms
- Bulk 10K variants: <60s

Indexing can dramatically reduce the search space by pre-computing which rows match specific values.

Key considerations:
1. **Index Type**: Hash-based (O(1) lookup) vs. tree-based (range queries) vs. bitmap (set operations)
2. **Memory Budget**: Indexes must fit within the 1GB heap constraint alongside rule data
3. **Query Pattern**: Evaluation intersects results across multiple columns—requires efficient AND operations

## Decision

Use **bitmap-based indexes** with operator-specific implementations:

### Index Types

| Index | Operators | Data Structure | Query Complexity |
|-------|-----------|----------------|------------------|
| `EqualityIndex` | EQ | HashMap<Value, long[]> | O(1) lookup + O(b) bitmap copy |
| `ComparisonIndex` | GT, GTE, LT, LTE | Sorted array + per-value bitmaps | O(log n) search + O(k×b) OR |

### CandidateBitmap Operations

```java
// 64-bit word operations for efficiency
long[] bitmap = CandidateBitmap.allOnes(rowCount);

// Intersect with each column's matching rows
CandidateBitmap.andInPlace(bitmap, equalityIndex.query(value));
CandidateBitmap.andInPlace(bitmap, comparisonIndex.query(operator, value));

// Find first matching row
int firstMatch = CandidateBitmap.findFirst(bitmap);
```

<!-- TODO(human): Expand on:
     - Why bitmaps over other index structures (B-trees, bloom filters)
     - The blank-rows handling (rows without conditions always match)
     - Memory estimates for typical table sizes
-->

### Evaluation Algorithm

1. Start with `allOnes` bitmap (all rows are candidates)
2. For each input column with an index:
   - Query index to get matching rows bitmap
   - AND with candidate bitmap (intersection)
3. Iterate remaining candidates in priority order
4. For each candidate, verify non-indexed columns match
5. Return first fully-matching row (or all matches for bulk)

## Alternatives Considered

<!-- TODO(human): Document alternatives such as:
     1. B-tree indexes (like databases)
     2. Bloom filters for fast negative lookups
     3. No indexes (pure linear scan with SIMD)
     4. Inverted indexes (term → document style)
-->

## Consequences

### Positive

<!-- TODO(human): List benefits like:
     - O(1) bitmap AND reduces candidates exponentially
     - Memory-efficient: ~625KB per 5M rows per index
     - Naturally supports OR via bitmap union
-->

### Negative

<!-- TODO(human): List drawbacks like:
     - High-cardinality columns create many small bitmaps
     - Range queries (BETWEEN) don't benefit from these indexes
     - Index building adds to compilation time
-->

### Neutral

- LSB-first bit ordering within 64-bit words matches Java's `Long.numberOfTrailingZeros()`.
- Blank cells (no condition) are tracked separately and always included in query results.

## References

- `CandidateBitmap.java` - Bitmap operations utility
- `EqualityIndex.java` - Hash-based index for EQ
- `ComparisonIndex.java` - Sorted index for comparisons
- `LoadedRulesetImpl.java` - Index-accelerated evaluation (lines 119-147)
- Related: ADR-0002 (Columnar Storage) for presence bitmaps
