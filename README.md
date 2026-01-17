# Kisoku
*Under development* 

Kisoku (pronounced "ki-so-ku") is a Java decision-table rule engine. The name
comes from the Japanese term for "rule" or "regulation." The library compiles
large decision tables into an execution-optimized form, supports indexed
evaluation, and provides deterministic results for single and bulk inputs.

## Status
- CSV sources only (JSON/Database loaders will be added later).
- **Validator**: Implemented - validates CSV structure, operators, and schema.
- **Compiler**: Implemented - compiles CSV to binary artifact format.
- **Loader**: Not yet implemented - loads compiled artifacts for evaluation.
- See `docs/PRD.md` for requirements and constraints.
- Not ready for prime time yet.

## Module Structure
- **kisoku-api** - Public API contracts (interfaces, models, exceptions). Consumers depend on this.
- **kisoku-runtime** - Implementation (validator, compiler, loader). No exports - discovered via ServiceLoader.

## Decision Table Format (CSV)
CSV uses two header rows:
1) Column names (ALL CAPS).
2) Operators for each column (ALL CAPS), fixed per column.
Data rows contain only operands (no operator prefixes).

Column conventions:
- Inputs: any column whose operator is not `SET`
- Outputs: columns whose operator is `SET`
- Test-only: `TEST_` prefix (included in all artifacts with flag `0x02`, can be excluded at evaluation time)
- Reserved: `RULE_ID`, `PRIORITY` (default priority column, configurable via `CompileOptions`)

Cell encoding:
- `BETWEEN_*` / `NOT_BETWEEN_*`: `(min,max)`
- `IN` / `NOT IN`: `(A,B,C)`
- Blank cell means "no condition"

Example:
```text
RULE_ID,PRIORITY,AGE,REGION,DISCOUNT
RULE_ID,PRIORITY,BETWEEN_INCLUSIVE,IN,SET
R1,10,(18,29),(APAC,EMEA),0.05
R2,20,,(APAC,EMEA),0.10
```

Rule selection:
- If a priority column exists, priority takes precedence.
- You can override selection with `RuleSelectionPolicy` in `CompileOptions`.

## Build and Test
```bash
mvn -q -DskipTests package
mvn test
mvn verify
mvn spotless:apply
```

Scale tests are gated:
```bash
mvn -Dkisoku.runScaleTests=true -Dkisoku.scaleRows=5000000 test
```

## Library Usage (Sketch)
```java
import in.systemhalted.kisoku.api.*;
import in.systemhalted.kisoku.api.validation.*;
import in.systemhalted.kisoku.api.compilation.*;
import in.systemhalted.kisoku.api.loading.*;
import in.systemhalted.kisoku.api.evaluation.*;

// Define schema for non-reserved columns
Schema schema = Schema.builder()
    .column("AGE", ColumnType.INTEGER)
    .column("REGION", ColumnType.STRING)
    .column("DISCOUNT", ColumnType.DECIMAL)
    .build();

DecisionTableSource source = DecisionTableSources.csv(Path.of("examples/pricing.csv"));
ValidationResult validation = Kisoku.validator().validate(source, schema);
CompiledRuleset compiled = Kisoku.compiler()
    .compile(source, CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));
try (LoadedRuleset ruleset = Kisoku.loader().load(compiled, LoadOptions.memoryMap())) {
  DecisionOutput output = ruleset.evaluate(DecisionInput.of(Map.of("AGE", 25)));
}
```

## Schema API
An external schema defines column types for validation and compilation:
- `Schema.builder().column(name, type).build()` - fluent schema construction
- Supported types: `STRING`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIMESTAMP`
- Reserved columns (`RULE_ID`, `PRIORITY`) have implicit types and don't need declaration
