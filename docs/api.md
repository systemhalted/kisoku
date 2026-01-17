# API Sketch

## Goals
- Provide a small, stable Java library API with explicit lifecycle stages.
- Keep loaded rulesets immutable and safe for concurrent evaluation.
- Separate IO/parsing from compilation and runtime evaluation.

## Non-Goals
- No network service or database client; DB integration is via user-provided sources.
- JSON/Database loaders are not implemented yet (CSV only for now).

## Package Layout
- `in.systemhalted.kisoku.api` public-facing types and lifecycle interfaces.
- `in.systemhalted.kisoku.api.compiler` compilation and artifact serialization.
- `in.systemhalted.kisoku.runtime` loader and evaluator implementations.
- `in.systemhalted.kisoku.io` CSV readers and source adapters.

## Lifecycle Overview
1) `validate(source, schema)` -> `ValidationResult`
2) `compile(source, CompileOptions.production(schema))` -> `CompiledRuleset`
3) `load(compiled, LoadOptions)` -> `LoadedRuleset`
4) `evaluate(input)` or `evaluateBulk(base, variants)`

## Public Interfaces (Sketch)
```java
package in.systemhalted.kisoku.api;

public interface DecisionTableSource {
  String name();
  TableFormat format(); // CSV, JSON, DATABASE
  InputStream openStream() throws IOException;
}

public enum TableFormat {
  CSV, JSON, DATABASE
}

public interface RulesetValidator {
  ValidationResult validate(DecisionTableSource source, Schema schema);
}

public interface RulesetCompiler {
  CompiledRuleset compile(DecisionTableSource source, CompileOptions options);
}

public interface RulesetLoader {
  LoadedRuleset load(CompiledRuleset compiled, LoadOptions options);
}

public interface LoadedRuleset extends AutoCloseable {
  DecisionOutput evaluate(DecisionInput input);
  BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants);
  RulesetMetadata metadata();
  @Override void close();
}
```

## Schema API
```java
public enum ColumnType {
  STRING, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP
}

public final class Schema {
  public static Builder builder();
  public Optional<ColumnSchema> column(String name);
  public List<ColumnSchema> columns();
  public boolean hasColumn(String name);

  public static final class Builder {
    public Builder column(String name, ColumnType type);
    public Builder column(String name, ColumnType type, boolean nullable);
    public Schema build();
  }
}
```

## Data Contracts (Sketch)
```java
package in.systemhalted.kisoku.api;

public final class DecisionInput {
  public static DecisionInput of(Map<String, Object> values) { /* ... */ }
  public Optional<Object> get(String key) { /* ... */ }
}

public final class DecisionOutput {
  public String ruleId();
  public Map<String, Object> outputs();
  public Optional<MatchDiagnostics> diagnostics();
}

public final class BulkResult {
  public List<DecisionOutput> results();
}
```

## Column Naming Conventions
- Reserved identifiers are ALL CAPS.
- Reserved columns include `RULE_ID` and `PRIORITY` (configurable via `CompileOptions`).
- Column roles are defined by the operator row: `SET` marks outputs, all other
  operators are inputs.
- Test-only columns are prefixed with `TEST_` and removed from production artifacts.

## CSV Header Rows
- CSV uses two header rows: the first row defines column names, the second row
  defines operators for each column.
- Operators are fixed per column and must be ALL CAPS.
- For `RULE_ID` and `PRIORITY`, repeat the column name in the operator row.
- Output columns use `SET` as the operator (for now).
- Blank cells in data rows mean "no condition" for that column.

Example CSV:
```text
RULE_ID,PRIORITY,AGE,REGION,DISCOUNT
RULE_ID,PRIORITY,BETWEEN_INCLUSIVE,IN,SET
R1,10,(18,29),(APAC,EMEA),0.05
R2,20,,(APAC,EMEA),0.10
```

## Operator Set
Operator row tokens (ALL CAPS):
- `RULE_ID`, `PRIORITY`, `SET`
- `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`
- `BETWEEN_INCLUSIVE`, `BETWEEN_EXCLUSIVE`
- `NOT_BETWEEN_INCLUSIVE`, `NOT_BETWEEN_EXCLUSIVE`
- `IN`, `NOT IN`

Accepted operator aliases in the operator row:
- `=` → `EQ`
- `!=` → `NE`
- `>` → `GT`
- `>=` → `GTE`
- `<` → `LT`
- `<=` → `LTE`
- `BETWEEN` → `BETWEEN_INCLUSIVE`
- `NOT BETWEEN` → `NOT_BETWEEN_INCLUSIVE`

## Cell Value Encoding
- `BETWEEN_*` and `NOT_BETWEEN_*` values use `(min,max)`.
- `IN` and `NOT IN` values use `(A,B,C)` (comma-separated).
- Empty cells mean no condition.
- Values are trimmed (whitespace is ignored around tokens).
- Operands may contain commas and are not quoted; parsing splits on commas only
  when not inside parentheses.

## Type Map
- Clients must supply a type map for all input and output columns (including `TEST_` columns).
- Type map is required at compile time and may be validated at load time.
- `RULE_ID` and `PRIORITY` have fixed types (string and int).
- String comparisons are case-sensitive by default; override via `CompileOptions`.

Supported types:
- `STRING`, `BOOLEAN`, `CHARACTER`
- `INT`, `LONG`, `DOUBLE`, `DECIMAL`
- `DATE` (ISO-8601 date)
- `TIMESTAMP` (ISO-8601 local date-time)
- `TIMESTAMP_TZ` (ISO-8601 with timezone offset or `Z`)
- `CHARACTER` values must be length 1.

## Operator Storage (Conceptual)
- `RULE_ID`: stored per row, typically dictionary-encoded.
- `PRIORITY`: required per row when the column exists. Can be overridden by CompileOptions.
- `BETWEEN_*`/`NOT_BETWEEN_*`: store `min` and `max` values plus a presence flag.
- `IN`/`NOT IN`: store a value list and a presence flag.
- `SET`: outputs may be blank, but each row must have at least one output value.

## Options and Configuration
- `CompileOptions` includes `artifactKind` (TEST_INCLUSIVE, PRODUCTION), rule selection,
  and indexing profile hints.
- `RuleSelectionPolicy` supports `AUTO`, `PRIORITY`, and `FIRST_MATCH`.
  - `AUTO` uses priority when a priority column is present; otherwise it uses
    deterministic row order (first-match).
- Reserved column names and keywords defined by the library are ALL CAPS.
  Avoid collisions with user-defined column names.
- `LoadOptions` includes memory mapping, index prewarm, and index load mode.
- `EvaluationOptions` (optional) can control diagnostics and strictness.

## Errors
- `ValidationException` for schema/test-cell errors.
- `CompilationException` for encoding or artifact failures.
- `LoadException` for incompatible or corrupted artifacts.
- `EvaluationException` for invalid inputs or runtime issues.

## Extensibility
- Provide custom sources by implementing `DecisionTableSource`.
- Provide IO helpers in `in.systemhalted.kisoku.io` such as
  `DecisionTableSources.csv(Path)` and `DecisionTableSources.json(Path)`.
- Keep public API minimal; implementation details live in `compiler` and `runtime`.

## Example Usage (Sketch)
```java
// Define schema for non-reserved columns
Schema schema = Schema.builder()
    .column("AGE", ColumnType.INTEGER)
    .column("REGION", ColumnType.STRING)
    .column("DISCOUNT", ColumnType.DECIMAL)
    .build();

DecisionTableSource source = DecisionTableSources.csv(Path.of("examples/pricing.csv"));
RulesetValidator validator = Kisoku.validator();
RulesetCompiler compiler = Kisoku.compiler();
RulesetLoader loader = Kisoku.loader();

ValidationResult validation = validator.validate(source, schema);
if (!validation.isOk()) {
  throw new ValidationException(validation.issues());
}

CompiledRuleset compiled = compiler.compile(
    source,
    CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));
try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
  DecisionInput input = DecisionInput.of(Map.of("AGE", 42, "REGION", "APAC"));
  DecisionOutput output = ruleset.evaluate(input);
}
```

## Notes for Library Consumers
- Keep `LoadedRuleset` instances long-lived and share across threads.
- Use production artifacts for runtime; keep test-inclusive artifacts for validation only.
- Bulk evaluation applies base inputs first, then overlays variant inputs.
- Keywords in expressions are ALL CAPS (e.g., `BETWEEN_INCLUSIVE`, `IN`, `NOT IN`).
