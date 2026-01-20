# ADR-0008: Dual Artifact Architecture (Test/Production)

## Status

Accepted

## Context

Decision tables often include columns used only for testing and validation:

```csv
RULE_ID,PRIORITY,AGE,REGION,DISCOUNT,TEST_EXPECTED_DISCOUNT,TEST_SCENARIO
RULE_ID,PRIORITY,GTE,IN,SET,SET,SET
R1,10,18,(APAC,EMEA),0.05,0.05,young_apac_emea
R2,20,30,(NA),0.10,0.10,adult_na
```

These `TEST_*` columns serve multiple purposes:
1. **Validation**: Verify expected outputs during compilation
2. **Documentation**: Describe what each rule tests
3. **Regression**: Compare actual vs. expected in CI pipelines

However, in production:
- Test columns waste memory (potentially millions of values)
- Test data may contain sensitive information
- Evaluation should not access test-only columns

The system needs to support both use cases from the same source CSV.

## Decision

Implement **dual artifact architecture** with `ArtifactKind` enum:

### Artifact Kinds

```java
public enum ArtifactKind {
    PRODUCTION,      // Excludes TEST_* columns at evaluation time
    TEST_INCLUSIVE   // Includes TEST_* columns for validation
}
```

### Column Flag

Test-only columns are marked with flag `0x02` in the binary format:

```java
// Column flags (combinable via bitwise OR)
0x01 = nullable
0x02 = test-only
0x03 = nullable + test-only
```

<!-- TODO(human): Expand on:
     - How TEST_ prefix is detected during compilation
     - The artifact_kind byte in the header (offset 8)
     - How LoadedRulesetImpl skips test columns during evaluation
     - CompileOptions.production() vs. CompileOptions.test()
-->

### Compilation Options

```java
// Production: skip TEST_* columns during evaluation
CompileOptions.production(schema)

// Testing: include TEST_* columns for validation
CompileOptions.test(schema)
```

## Alternatives Considered

<!-- TODO(human): Document alternatives such as:
     1. Separate CSV files for test and production
     2. Strip TEST_* columns during compilation (no flag)
     3. Runtime configuration instead of compile-time
-->

## Consequences

### Positive

<!-- TODO(human): List benefits like:
     - Single source of truth for rules and tests
     - Production artifacts are smaller
     - Test validation happens at compile time
-->

### Negative

<!-- TODO(human): List drawbacks like:
     - Two artifact types to manage
     - TEST_ prefix convention must be documented
     - Flag adds complexity to column definition
-->

### Neutral

- Both artifact kinds use the same binary format; only the `artifact_kind` header byte differs.
- Test columns are always included in the artifact; the flag controls evaluation-time behavior.

## References

- `ArtifactKind.java` - Enum definition
- `docs/artifact-format.md` - Binary format with column flags
- `CompileOptions.java` - Compilation configuration
- `CsvRulesetCompiler.java` - TEST_ prefix detection
- `LoadedRulesetImpl.java` - Test column exclusion during evaluation
