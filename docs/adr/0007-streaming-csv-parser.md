# ADR-0007: Streaming CSV Parser (Parentheses-Aware)

## Status

Accepted

## Context

Decision table CSVs have a unique structure that standard CSV parsers don't handle well:

```csv
RULE_ID,PRIORITY,AGE,REGION,DISCOUNT
RULE_ID,PRIORITY,BETWEEN_INCLUSIVE,IN,SET
R1,10,(18,29),(APAC,EMEA),0.05
R2,20,(30,65),(NA,LATAM),0.10
```

Challenges:
1. **Parenthesized values**: `(18,29)` and `(APAC,EMEA)` contain commas that are NOT field delimiters
2. **Two-header format**: Row 1 is column names, row 2 is operators
3. **Memory constraints**: 20M rows cannot be loaded into memory at once
4. **Whitespace handling**: Cell values should be trimmed consistently

Standard CSV libraries (Apache Commons CSV, OpenCSV) either:
- Split on all commas, breaking parenthesized values
- Require quoted fields, which business users forget to add
- Load entire file into memory

## Decision

Implement a **custom streaming CSV parser** with parentheses-depth tracking:

### Parsing Algorithm

```java
// Track parentheses depth to distinguish delimiters from value content
int parenDepth = 0;
for (char c : line) {
    if (c == '(') parenDepth++;
    else if (c == ')') parenDepth--;
    else if (c == ',' && parenDepth == 0) {
        // This comma is a field delimiter
        fields.add(currentField.toString().trim());
        currentField.setLength(0);
    }
}
```

<!-- TODO(human): Expand on:
     - Error handling for unbalanced parentheses
     - Why streaming (BufferedReader) vs. full load
     - Row counter for error reporting
     - Whitespace-only line handling
-->

### Interface Design

```java
public interface CsvRowReader extends Closeable {
    String[] readRow() throws IOException;
}

// Streaming implementation
public class StreamingCsvRowReader implements CsvRowReader {
    // Returns null at EOF, throws on parse errors
}
```

## Alternatives Considered

<!-- TODO(human): Document alternatives such as:
     1. Standard CSV library with quoted fields requirement
     2. Custom delimiter (e.g., | or tab) instead of comma
     3. JSON or YAML format instead of CSV
     4. Pre-processing step to quote parenthesized values
-->

## Consequences

### Positive

<!-- TODO(human): List benefits like:
     - No quoting required for business users
     - Constant memory regardless of file size
     - Clear error messages with row numbers
-->

### Negative

<!-- TODO(human): List drawbacks like:
     - Custom parser to maintain
     - Nested parentheses not supported (not needed)
     - Can't use standard CSV tooling
-->

### Neutral

- UTF-8 encoding is assumed and enforced via `StandardCharsets.UTF_8`.
- Empty lines and whitespace-only lines are skipped silently.

## References

- `StreamingCsvRowReader.java` - Parser implementation
- `CsvRulesetCompiler.java` - Uses parser during compilation
- `docs/artifact-format.md` - CSV format specification
- Related: ADR-0006 (Operator Aliasing) for operator parsing
