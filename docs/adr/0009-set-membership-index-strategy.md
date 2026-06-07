# ADR-0009: Set Membership Index Strategy

## Status

Accepted

## Context

IN and NOT_IN operators match an input value against a *set* of values defined per row. For example, a REGION column with operator IN might contain `(APAC, EMEA)` in one row and `(NA, LATAM)` in another. Evaluation must determine which rows' sets contain the input value.

The columnar storage format (ADR-0002) already encodes sets efficiently:

```
presence_bitmap  (ceil(row_count/8) bytes)
list_offsets[]   (4 bytes each, into all_values)
list_lengths[]   (2 bytes each, set cardinality)
all_values[]     (4 bytes each, all set values packed)
```

Two viable indexing strategies exist:
1. **Columnar scan**: Iterate rows, check membership via offsets/lengths (no additional memory)
2. **Inverted index**: Pre-build value→rows mapping at load time (O(1) query)

The PRD constraints demand:
- Up to 20M rows, 60-120 input columns
- Single evaluation p95: <250ms
- JVM heap: <1GB

## Decision

Build an **inverted index** at load time that maps each unique value to the rows containing it:

```java
Map<Integer, long[]> valueToRowBitmap;  // value → rows containing it
long[] noConditionRows;                  // blank cells (always match)
long[] allConditionRows;                 // for NOT_IN complement
```

### Query Algorithm

**For IN operator:**
```java
long[] match = valueToRowBitmap.get(inputValue);
return OR(match, noConditionRows);  // Rows containing value OR blank rows
```

**For NOT_IN operator:**
```java
long[] match = valueToRowBitmap.get(inputValue);
return OR(ANDNOT(allConditionRows, match), noConditionRows);
// (Rows with conditions that DON'T contain value) OR blank rows
```

**Query complexity**: O(1) lookup + O(b) bitmap operations, where b = ceil(rowCount/64).

### Index Construction

At load time, scan columnar data once:

```java
for (int row = 0; row < rowCount; row++) {
    if (hasCondition(row)) {
        set(allConditionRows, row);
        for (int value : getSetValues(row)) {
            valueToRowBitmap.computeIfAbsent(value, _ -> new long[])
                            .set(row);
        }
    } else {
        set(noConditionRows, row);
    }
}
```

## Alternatives Considered

| Alternative | Pros | Cons | Why Rejected |
|-------------|------|------|--------------|
| **Columnar scan** | Zero additional memory, simpler implementation | O(n × avg_set_size) per query, unpredictable latency | At 5M rows with avg 5 values per set, each query scans 25M values. This violates the 250ms p95 requirement for multi-column evaluation |
| **Lazy index** | Defers construction cost, only indexes frequently queried values | First query slow, adds complexity for cache management | Violates predictable p95 requirement; some user inputs would experience 10x+ latency spikes |
| **Hybrid (index hot values, scan cold)** | Balances memory and coverage | Implementation complexity, still has tail latency for cold values | Premature optimization; full index fits within memory budget |

## Memory Analysis

Each bitmap requires `ceil(rowCount/64) × 8` bytes:

| Row Count | Bytes per Bitmap |
|-----------|------------------|
| 1M rows   | 125 KB          |
| 5M rows   | 625 KB          |
| 20M rows  | 2.5 MB          |

**Total index memory for a column:**

```
memory = (2 + uniqueValueCount) × bytesPerBitmap
       = (noConditionRows + allConditionRows + valueToRowBitmap.size()) × ceil(rowCount/64) × 8
```

**Worked examples at 5M rows:**

| Scenario | Unique Values | Index Size |
|----------|---------------|------------|
| Low cardinality (e.g., REGION) | 10 | 7.5 MB |
| Medium cardinality (e.g., PRODUCT_CATEGORY) | 100 | 64 MB |
| High cardinality (e.g., PRODUCT_ID) | 1,000 | 627 MB |
| Very high cardinality | 10,000 | 6.25 GB ❌ |

For columns with >1000 unique values, index memory exceeds acceptable bounds. However:
- Most IN/NOT_IN columns have low cardinality (that's why they're sets, not equality checks)
- High-cardinality set membership is rare in real decision tables
- Future enhancement: fall back to columnar scan for high-cardinality columns

## Consequences

### Positive

- **O(1) lookup**: Query time independent of set sizes, enabling predictable p95 latency
- **Consistent pattern**: Follows EqualityIndex and ComparisonIndex design from ADR-0005
- **Efficient NOT_IN**: Bitmap complement via ANDNOT is O(b), not O(n)
- **Blank-row handling**: noConditionRows bitmap integrates seamlessly with query logic

### Negative

- **Memory overhead**: Proportional to unique value count across all sets in the column
- **Load time cost**: Must scan all columnar data and build bitmaps before first query
- **Duplicated information**: Index stores value→rows mapping that's also implicit in columnar offsets/lengths

### Neutral

- **Integer keys**: Dictionary compression (ADR-0002) means valueToRowBitmap keys are 4-byte dictionary IDs, not strings—efficient HashMap keys
- **No partial indexing**: Unlike databases, we don't support indexing "hot" values only—all values are indexed equally

## References

- ADR-0002: Columnar Storage (source data format with offsets/lengths/values)
- ADR-0005: Bitmap-Based Indexing (established index pattern, CandidateBitmap operations)
- `SetMembershipIndex.java`: Index implementation
- `SetMembershipColumnDecoder.java`: Source columnar data with package-private accessors
- `CandidateBitmap.java`: Bitmap operations (set, andInPlace, andNotInPlace, orInPlace)
