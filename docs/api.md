# API Sketch

## Goals
- Provide a small, stable Java library API with explicit lifecycle stages.
- Keep loaded rulesets immutable and safe for concurrent evaluation.
- Separate IO/parsing from compilation and runtime evaluation.

## Non-Goals
- No network service or database client; DB integration is via user-provided sources.

## Package Layout
- `in.systemhalted.kisoku.api` public-facing types and lifecycle interfaces.
- `in.systemhalted.kisoku.compiler` compilation and artifact serialization.
- `in.systemhalted.kisoku.runtime` loader and evaluator implementations.
- `in.systemhalted.kisoku.io` CSV/JSON readers and source adapters.

## Lifecycle Overview
1) `validate(source)` -> `ValidationResult`
2) `compile(source, CompileOptions)` -> `CompiledRuleset`
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
  ValidationResult validate(DecisionTableSource source);
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
DecisionTableSource source = DecisionTableSources.csv(Path.of("examples/pricing.csv"));
ValidationResult validation = validator.validate(source);
if (!validation.ok()) {
  throw new ValidationException(validation.issues());
}

CompiledRuleset compiled = compiler.compile(
    source,
    CompileOptions.production().withRuleSelection(RuleSelectionPolicy.AUTO));
try (LoadedRuleset ruleset = loader.load(compiled, LoadOptions.memoryMap())) {
  DecisionInput input = DecisionInput.of(Map.of("age", 42, "region", "APAC"));
  DecisionOutput output = ruleset.evaluate(input);
}
```

## Notes for Library Consumers
- Keep `LoadedRuleset` instances long-lived and share across threads.
- Use production artifacts for runtime; keep test-inclusive artifacts for validation only.
- Bulk evaluation applies base inputs first, then overlays variant inputs.
- Keywords in expressions are ALL CAPS (e.g., `BETWEEN`, `IN`, `NOT IN`, `NOT BETWEEN`).
