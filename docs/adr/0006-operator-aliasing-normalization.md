# ADR-0006: Operator Aliasing and Normalization

## Status

Accepted

## Context

Decision tables are authored by business users who may use different notations for the same operator:

| Intent | Possible Notations |
|--------|-------------------|
| Equals | `EQ`, `=`, `EQUAL`, `EQUALS` |
| Not equals | `NE`, `!=`, `<>`, `NOT EQUAL` |
| Greater than | `GT`, `>`, `GREATER THAN` |
| Between inclusive | `BETWEEN_INCLUSIVE`, `BETWEEN`, `BETWEEN INCLUSIVE` |

CSV headers may contain:
- Inconsistent casing: `eq`, `EQ`, `Eq`
- Extra whitespace: `NOT  EQUAL`, ` GT `
- Symbolic vs. textual: `>=` vs. `GTE`

The system must accept all reasonable variations while maintaining a single canonical representation internally.

## Decision

Implement **operator aliasing with normalization** in the `Operator` enum:

### Normalization Rules

```java
private static String normalize(String token) {
    return token.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");  // Collapse whitespace
}
```

### Alias Mapping

<!-- TODO(human): Describe the TOKEN_MAP structure and key aliases:
     - Symbolic aliases (=, !=, <>, >, >=, <, <=)
     - Multi-word aliases (NOT EQUAL, NOT BETWEEN, etc.)
     - Why BETWEEN defaults to BETWEEN_INCLUSIVE
-->

```java
// All these resolve to Operator.NE
Operator.fromToken("NE")          // canonical
Operator.fromToken("!=")          // symbolic
Operator.fromToken("not equal")   // textual (normalized)
Operator.fromToken("  NOT  EQUAL  ")  // whitespace variations
```

### Error Handling

```java
// Unknown operators throw with clear message
Operator.fromToken("UNKNOWN")
// → IllegalArgumentException: "Unsupported operator: UNKNOWN"
```

## Alternatives Considered

<!-- TODO(human): Document alternatives such as:
     1. Strict canonical-only parsing (no aliases)
     2. Fuzzy matching / Levenshtein distance
     3. Per-format alias configuration (CSV vs. Excel)
-->

## Consequences

### Positive

<!-- TODO(human): List benefits like:
     - Business users can use familiar notation
     - Reduces validation errors from formatting
     - Single source of truth for operator semantics
-->

### Negative

<!-- TODO(human): List drawbacks like:
     - Ambiguity risk if aliases overlap
     - Documentation must list all accepted forms
-->

### Neutral

- The `TOKEN_MAP` is immutable (`Collections.unmodifiableMap`) and initialized statically.
- Normalization is idempotent—applying it twice produces the same result.

## References

- `Operator.java` - Enum with aliasing logic
- `StreamingCsvRowReader.java` - Uses operator parsing during CSV reading
- Related: ADR-0007 (Streaming CSV Parser) for parsing context
