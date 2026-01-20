# ADR-0004: Sealed Interface Decoder Hierarchy

## Status

Accepted

## Context

Kisoku supports 14 operators with different matching semantics:

| Category | Operators | Matching Logic |
|----------|-----------|----------------|
| Scalar | EQ, NE, GT, GTE, LT, LTE, SET | Single value comparison |
| Range | BETWEEN_*, NOT_BETWEEN_* | Two-value boundary check |
| Set Membership | IN, NOT_IN | Contains check against value list |

Each operator requires different:
1. **Data layout**: Scalar needs one value array; range needs min/max arrays; set needs offset/length/values arrays
2. **Matching logic**: EQ checks equality; BETWEEN checks `min <= x <= max`; IN checks set membership
3. **Index compatibility**: EQ benefits from hash index; GT/LT benefit from sorted index

The decoder abstraction must be:
- **Type-safe**: Prevent mixing incompatible decoders
- **Exhaustive**: Compiler should verify all cases are handled
- **Extensible**: Adding new operators should be explicit

## Decision

Use a **sealed interface hierarchy** with three permitted implementations:

```java
public sealed interface ColumnDecoder
    permits ScalarColumnDecoder, RangeColumnDecoder, SetMembershipColumnDecoder {

    boolean matches(int rowIndex, Object inputValue);
    boolean hasCondition(int rowIndex);
    Object getValue(int rowIndex);
}
```

<!-- TODO(human): Expand on the decision:
     - Why sealed interfaces over abstract classes or enums
     - How the factory method routes operators to decoder types
     - The role of ColumnDefinition in decoder construction
-->

### Decoder Implementations

| Decoder | Operators | Data Access Pattern |
|---------|-----------|---------------------|
| `ScalarColumnDecoder` | EQ, NE, GT, GTE, LT, LTE, SET, RULE_ID, PRIORITY | `values[rowIndex]` |
| `RangeColumnDecoder` | BETWEEN_*, NOT_BETWEEN_* | `min[rowIndex]`, `max[rowIndex]` |
| `SetMembershipColumnDecoder` | IN, NOT_IN | `offsets[rowIndex]`, `lengths[rowIndex]`, `allValues[]` |

## Alternatives Considered

<!-- TODO(human): Document alternatives such as:
     1. Single decoder class with operator-based switch in matches()
     2. Visitor pattern for operator dispatch
     3. Abstract class hierarchy instead of sealed interface
-->

## Consequences

### Positive

<!-- TODO(human): List benefits like:
     - Compile-time exhaustiveness checking in switch expressions
     - Clear separation of data layout concerns
     - Easy to add operator-specific optimizations
-->

### Negative

<!-- TODO(human): List drawbacks like:
     - More classes to maintain
     - Indirection cost for decoder selection
-->

### Neutral

- Java 17+ sealed interfaces are a natural fit for this finite set of decoder types.
- The `ColumnDefinition` record captures metadata needed for decoder construction.

## References

- `ColumnDecoder.java` - Sealed interface definition
- `ScalarColumnDecoder.java`, `RangeColumnDecoder.java`, `SetMembershipColumnDecoder.java` - Implementations
- `ColumnDefinition.java` - Column metadata record
- Related: ADR-0002 (Columnar Storage) for encoding formats
