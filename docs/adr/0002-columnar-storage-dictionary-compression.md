# ADR-0002: Columnar Storage with Dictionary Compression

## Status

Deaft

## Context

Kisoku must support decision tables with up to 20M rows, 60-120 input columns, and 20-50 output columns while keeping JVM heap usage under 1GB. The storage format directly impacts:

1. **Memory Efficiency**: At 20M rows × 100 columns, naive object-per-cell storage would create 2 billion Java objects—each with 12-16 bytes of object header overhead alone.

2. **Evaluation Performance**: Decision table evaluation filters rows by checking input columns against conditions. The storage layout determines cache utilization during these scans.

3. **String Deduplication**: Decision tables often have low-cardinality string columns (e.g., REGION with values like "APAC", "EMEA", "NA"). Storing "APAC" 5 million times wastes memory.

4. **Compression Opportunities**: Similar values grouped together compress better than interleaved heterogeneous data.

## Decision

Store rule data in **columnar format** with **dictionary compression** for strings:

### Columnar Layout

Data is organized column-by-column rather than row-by-row:

```
Row-based (rejected):     Columnar (chosen):
┌─────┬─────┬─────┐      ┌─────────────────┐
│ R1  │ 18  │ APAC│      │ R1, R2, R3, ... │ ← RULE_ID column
├─────┼─────┼─────┤      ├─────────────────┤
│ R2  │ 25  │ EMEA│      │ 18, 25, 30, ... │ ← AGE column
├─────┼─────┼─────┤      ├─────────────────┤
│ R3  │ 30  │ APAC│      │ APAC,EMEA,APAC..│ ← REGION column
└─────┴─────┴─────┘      └─────────────────┘
```

### Dictionary Compression

All string values are stored once in a dictionary and referenced by 4-byte integer IDs:

```java
// StringDictionary assigns stable IDs
public int getOrAssign(String value) {
    return stringToId.computeIfAbsent(value, k -> nextId++);
}
```

- ID 0 is reserved for null/empty values
- Dictionary entries: 2-byte length prefix + UTF-8 bytes
- Maximum string length: 65,535 bytes (enforced by `writeShort`)

### Encoding by Operator Type

Each column uses operator-specific encoding (see `ColumnEncoder` hierarchy):

| Operator Type | Storage Format |
|---------------|----------------|
| Scalar (EQ, GT, SET, etc.) | presence_bitmap + values[] |
| Range (BETWEEN_*) | presence_bitmap + min[] + max[] |
| Set (IN, NOT_IN) | presence_bitmap + offsets[] + lengths[] + all_values[] |

### Presence Bitmaps

Every column includes a presence bitmap (`ceil(row_count/8)` bytes) where bit i = 1 indicates row i has a condition. This enables:
- Skipping blank cells without sentinel values
- Fast population count for statistics
- MSB-first bit ordering for consistent byte layout

## Alternatives Considered

<!-- TODO(human): Please fill in the alternatives you considered and why they were rejected.
     Consider addressing:
     1. Row-based storage with object pooling - why wasn't this sufficient?
     2. Apache Arrow or other columnar libraries - why build custom?
     3. Different dictionary encodings (e.g., variable-length IDs, prefix compression)

     Example format:
     ### Alternative 1: Row-based storage with object pooling
     [Your reasoning here]

     ### Alternative 2: Apache Arrow integration
     [Your reasoning here]
-->

## Consequences

### Positive

- **Memory Efficiency**: Dictionary compression reduces string storage by 80-95% for typical low-cardinality columns. A column with 1000 unique values across 5M rows uses ~4KB dictionary + 20MB IDs instead of potentially hundreds of MB of duplicate strings.

- **Cache-Friendly Scans**: Column-wise filtering reads contiguous memory, maximizing CPU cache utilization. Evaluating `AGE > 18` scans only the AGE column's 20MB, not the entire table.

- **Compression-Ready**: Columnar layout groups similar values together. Future enhancements like run-length encoding or delta encoding become straightforward.

- **No Per-Cell Objects**: Raw `ByteBuffer` access with integer offsets eliminates object overhead entirely. 20M rows × 100 columns requires zero Java heap objects for cell data.

### Negative

- **Write Complexity**: Building columnar artifacts requires buffering all values per column before writing, increasing compilation memory usage temporarily.

- **Random Access Overhead**: Accessing a single row requires reading from multiple column buffers. This is acceptable because evaluation always processes columns, not individual rows.

- **Dictionary Lookup Cost**: String comparisons require dictionary ID resolution. Mitigated by comparing IDs directly when possible (equality checks).

### Neutral

- **Fixed 4-Byte IDs**: Using 4-byte IDs for all dictionaries simplifies code but uses more space than variable-length encoding for small dictionaries. The PRD's 1GB heap constraint is still achievable.

- **MSB-First Bitmaps**: The bit ordering choice (MSB-first in each byte) matches the artifact format specification and remains consistent across the codebase.

## References

- `docs/artifact-format.md` - Binary format specification
- `ColumnEncoder.java` - Abstract base for column encoding
- `StringDictionary.java` - Dictionary management implementation
- Related: ADR-0003 (Buffer Strategy) for how columnar data is loaded
