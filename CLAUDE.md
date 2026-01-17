# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
mvn -q -DskipTests package          # Compile and package
mvn test                             # Run unit tests
mvn verify                           # Run unit + integration tests + formatting check
mvn spotless:apply                   # Format code with google-java-format

# Scale tests (gated)
mvn -Dkisoku.runScaleTests=true -Dkisoku.scaleRows=5000000 test
```

Tests use JUnit 5. Unit tests are `*Test.java`, integration tests are `*IT.java`. Scale tests are tagged `scale`.

## Project Overview

Kisoku is a Java decision-table rule engine that compiles large decision tables into an execution-optimized form, supports indexed evaluation, and provides deterministic results for single and bulk inputs.

**Current status**: Under development. CSV parsing, operator handling, and validation are complete. Compiler and loader are stubs. See `docs/artifact-format.md` for the binary format specification.

## Architecture

### Lifecycle Flow

```
validate → compile → load → evaluate
```

1. **Validate**: `Kisoku.validator().validate(source, schema)` → `ValidationResult`
2. **Compile**: `Kisoku.compiler().compile(source, CompileOptions.production(schema))` → `CompiledRuleset`
3. **Load**: `Kisoku.loader().load(compiled, LoadOptions.memoryMap())` → `LoadedRuleset`
4. **Evaluate**: `ruleset.evaluate(input)` or `ruleset.evaluateBulk(base, variants)`

### Schema API

External schema is required for validation and compilation:
```java
Schema schema = Schema.builder()
    .column("AGE", ColumnType.INTEGER)
    .column("REGION", ColumnType.STRING)
    .column("DISCOUNT", ColumnType.DECIMAL)
    .build();
```
- Supported types: `STRING`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIMESTAMP`
- Reserved columns (`RULE_ID`, `PRIORITY`) don't need declaration

### Package Structure

- `in.systemhalted.kisoku` - Entry point (`Kisoku.java`) with factory methods
- `in.systemhalted.kisoku.api.*` - Public API contracts (interfaces, models, exceptions)
- `in.systemhalted.kisoku.runtime.*` - Implementation (compiler, loader, evaluator, CSV parsing)
- `in.systemhalted.kisoku.io` - Source I/O helpers

### Key Interfaces

- `DecisionTableSource` - Provides access to table data (name, format, input stream)
- `RulesetValidator` / `RulesetCompiler` / `RulesetLoader` - Lifecycle components
- `LoadedRuleset` - Immutable, thread-safe ruleset for evaluation
- `DecisionInput` / `DecisionOutput` - Typed I/O contracts

## CSV Format

Two-header format:
- Row 1: Column names (ALL CAPS)
- Row 2: Operators (ALL CAPS), fixed per column
- Data rows: Operands only (no operator prefixes)

```text
RULE_ID,PRIORITY,AGE,REGION,DISCOUNT
RULE_ID,PRIORITY,BETWEEN_INCLUSIVE,IN,SET
R1,10,(18,29),(APAC,EMEA),0.05
```

**Column types**:
- Inputs: operator ≠ `SET`
- Outputs: operator = `SET`
- Test-only: `TEST_*` prefix (stripped in PRODUCTION artifacts)
- Reserved: `RULE_ID`, `PRIORITY`

**Operators**: `EQ` (=), `NE` (!=), `GT` (>), `GTE` (>=), `LT` (<), `LTE` (<=), `BETWEEN_INCLUSIVE`, `BETWEEN_EXCLUSIVE`, `NOT_BETWEEN_INCLUSIVE`, `NOT_BETWEEN_EXCLUSIVE`, `IN`, `NOT IN`, `SET`

**Cell encoding**:
- Range operators: `(min,max)`
- Set operators: `(A,B,C)`
- Blank = no condition

## Design Constraints (from PRD)

- Up to 20M rows, 60-120 input columns, 20-50 output columns
- Single eval p95: <250ms; Bulk 10K variants: <60s
- JVM heap: <1GB (use off-heap/memory-mapped for large tables)
- No per-cell/per-row Java objects for million-row tables
- Immutable, thread-safe `LoadedRuleset`
- Deterministic evaluation (same inputs → same outputs)

## Code Style

- google-java-format via Spotless (run `mvn spotless:apply` before committing)
- 4-space indent for code, 2-space for YAML/JSON
- LF line endings
- One public class per file, no wildcard imports
- Immutable objects with defensive copies (`Map.copyOf`, etc.)
