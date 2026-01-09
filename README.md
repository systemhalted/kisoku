# Kisoku

Kisoku (pronounced "ki-so-ku") is a Java decision-table rule engine. The name
comes from the Japanese term for "rule" or "regulation." The library compiles
large decision tables into an execution-optimized form, supports indexed
evaluation, and provides deterministic results for single and bulk inputs.

## Status
- CSV sources only (JSON/Database loaders will be added later).
- API and functional tests are in place; implementation is in progress.
- See `docs/PRD.md` for requirements and constraints.
- Not ready for prime time yet.

## Decision Table Format (CSV)
CSV uses two header rows:
1) Column names (ALL CAPS).
2) Operators for each column (ALL CAPS), fixed per column.

Column conventions:
- Inputs: `IN_` prefix
- Outputs: `OUT_` prefix
- Test-only: `TEST_` prefix (stripped in production artifacts)
- Reserved: `RULE_ID`, `PRIORITY` (default priority column, configurable)

Cell encoding:
- `BETWEEN` / `NOT BETWEEN`: `(min,max)`
- `IN` / `NOT IN`: `(A,B,C)`
- Blank cell means "no condition"

Example:
```text
RULE_ID,PRIORITY,IN_AGE,IN_REGION,OUT_DISCOUNT
RULE_ID,PRIORITY,BETWEEN,IN,SET
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
DecisionTableSource source = DecisionTableSources.csv(Path.of("examples/pricing.csv"));
ValidationResult validation = Kisoku.validator().validate(source);
CompiledRuleset compiled = Kisoku.compiler()
    .compile(source, CompileOptions.production().withRuleSelection(RuleSelectionPolicy.AUTO));
try (LoadedRuleset ruleset = Kisoku.loader().load(compiled, LoadOptions.memoryMap())) {
  DecisionOutput output = ruleset.evaluate(DecisionInput.of(Map.of("IN_AGE", 25)));
}
```
