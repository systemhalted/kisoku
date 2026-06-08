# Getting Started with Kisoku

Kisoku is a Java decision-table rule engine. You author rules as a CSV decision
table, compile them once into a compact binary artifact, load that artifact into
an immutable in-memory ruleset, and evaluate inputs against it — for single
requests or in bulk. This guide walks through the full path end to end.

If you only want the API reference (every type and signature), see
[`docs/api.md`](api.md). For the design rationale behind these choices, see
[`docs/blog/design-decisions.md`](blog/design-decisions.md).

## 1. Add the dependency

Kisoku ships as two modules. Depend on **kisoku-api** at compile time and put
**kisoku-runtime** on the runtime classpath — the runtime registers the engine
implementations, which `kisoku-api` discovers through the JDK `ServiceLoader`.

```xml
<dependency>
  <groupId>in.systemhalted.kisoku</groupId>
  <artifactId>kisoku-api</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>in.systemhalted.kisoku</groupId>
  <artifactId>kisoku-runtime</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>runtime</scope>
</dependency>
```

Kisoku targets **Java 21**. If you compile your own application with the Java
Platform Module System, `requires in.systemhalted.kisoku.api;` is enough — the
runtime is a service provider and need not be `requires`d directly.

> If `Kisoku.compiler()` / `loader()` / `validator()` throws
> `IllegalStateException`, the runtime module is missing from the classpath.

## 2. Author a decision table (CSV)

A Kisoku CSV uses **two header rows**, then one row per rule:

1. **Row 1 — column names** (ALL CAPS).
2. **Row 2 — operators**, one per column, fixed for that column.
3. **Data rows — operands only** (no operator prefixes).

```text
RULE_ID,PRIORITY,REGION,AGE,DISCOUNT
RULE_ID,PRIORITY,IN,BETWEEN,SET
R1,30,(APAC,EMEA),(18,65),0.20
R2,20,(US),(21,70),0.15
R3,10,,,0.05
```

Column conventions:

- **Inputs** — any column whose operator is not `SET` (here `REGION`, `AGE`).
- **Outputs** — columns whose operator is `SET` (here `DISCOUNT`).
- **Reserved** — `RULE_ID` and `PRIORITY`; repeat the name in the operator row.
  They have implicit types and need no schema declaration.
- **Test-only** — columns prefixed `TEST_` are kept in the artifact (flag `0x02`)
  but never returned at evaluation time. See [§7](#7-test_-columns).

Cell encoding:

- Range operators (`BETWEEN_*`, `NOT_BETWEEN_*`) → `(min,max)`.
- Set operators (`IN`, `NOT_IN`) → `(A,B,C)`.
- A **blank cell means "no condition"** — that rule matches any input for that column.

In the table above, `R3` has blank `REGION`/`AGE`, so it matches everything — a
catch-all fallback with the lowest priority.

## 3. Define the schema

Validation and compilation require an external schema declaring the type of each
non-reserved column:

```java
import in.systemhalted.kisoku.api.Schema;
import in.systemhalted.kisoku.api.ColumnType;

Schema schema =
    Schema.builder()
        .column("REGION", ColumnType.STRING)
        .column("AGE", ColumnType.INTEGER)
        .column("DISCOUNT", ColumnType.DECIMAL)
        .build();
```

Supported `ColumnType`s: `STRING`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`,
`TIMESTAMP`. `RULE_ID` (string) and `PRIORITY` (int) are implicit — do not declare
them.

## 4. Validate, compile, load, evaluate

The lifecycle is explicit: **validate → compile → load → evaluate**. You reach the
engine through the `Kisoku` facade, which resolves each phase via `ServiceLoader`.

```java
import in.systemhalted.kisoku.api.DecisionTableSources;
import in.systemhalted.kisoku.api.Kisoku;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.RuleSelectionPolicy;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.validation.ValidationResult;
import java.nio.file.Path;
import java.util.Map;

var source = DecisionTableSources.csv(Path.of("pricing.csv"));

// 1) Validate — returns a result; it does not throw on rule problems.
ValidationResult validation = Kisoku.validator().validate(source, schema);
if (!validation.isOk()) {
  throw new IllegalStateException("Invalid table: " + validation.issues());
}

// 2) Compile — produces an in-memory artifact.
CompiledRuleset compiled =
    Kisoku.compiler()
        .compile(
            source,
            CompileOptions.production(schema).withRuleSelection(RuleSelectionPolicy.AUTO));

// 3) Load — LoadedRuleset is AutoCloseable; use try-with-resources.
// 4) Evaluate.
try (LoadedRuleset ruleset = Kisoku.loader().load(compiled, LoadOptions.memoryMap())) {
  DecisionInput input = DecisionInput.of(Map.of("REGION", "APAC", "AGE", 40));
  DecisionOutput output = ruleset.evaluate(input);

  System.out.println(output.ruleId());            // -> "R1"
  System.out.println(output.outputs().get("DISCOUNT")); // -> "0.20"
}
```

Notes:

- `DecisionInput.of(Map)` is the only constructor; a missing or `null` key means
  "no value" and matches blank cells. Use `DecisionInput.empty()` for no inputs.
- `evaluate` returns the single selected `DecisionOutput`. If no rule matches,
  read `output.ruleId()` / `output.outputs()` accordingly (see your table's
  fallback row).
- Rule selection follows `RuleSelectionPolicy` (next: [§6](#6-options-reference)).

## 5. Persist and reload the compiled artifact

Compilation is the expensive step; do it once and reuse the artifact. Write the
self-describing `.kss` file with `CompiledRuleset.writeTo(Path)`, then load it
later — even in a different process — with `RulesetLoader.load(Path, LoadOptions)`:

```java
Path artifact = Path.of("pricing.kss");
compiled.writeTo(artifact);          // write once (e.g. at build/startup)

// ...later, possibly in another JVM / pod...
try (LoadedRuleset ruleset = Kisoku.loader().load(artifact, LoadOptions.memoryMap())) {
  DecisionOutput out = ruleset.evaluate(DecisionInput.of(Map.of("REGION", "US", "AGE", 50)));
  // out.ruleId() -> "R2"
}
```

With `LoadOptions.memoryMap()`, `load(Path)` maps the file directly
(`FileChannel.map()`) and decoders read column data lazily through the mapped
buffer. Rule data stays **off-heap**, which is how Kisoku holds large tables under
the 1 GB heap budget. The same artifact loads identically across processes, so you
can compile in one place and serve from many.

## 6. Bulk evaluation

To score many inputs that share a common base, use `evaluateBulk`. It applies the
`base` input, then overlays each variant's values on top, and returns one
`DecisionOutput` per variant, in order:

```java
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import java.util.List;

DecisionInput base = DecisionInput.of(Map.of("REGION", "APAC"));
List<DecisionInput> variants =
    List.of(
        DecisionInput.of(Map.of("AGE", 25)),
        DecisionInput.of(Map.of("AGE", 45)),
        DecisionInput.of(Map.of("AGE", 70)));

BulkResult result = ruleset.evaluateBulk(base, variants);
for (DecisionOutput out : result.results()) {
  System.out.println(out.ruleId() + " -> " + out.outputs());
}
```

`evaluateBulk` is deterministic and gives the same answer as calling `evaluate`
on each merged input independently.

## 7. Options reference

**`LoadOptions`** (how the ruleset is loaded):

| Factory / method | Effect |
|---|---|
| `LoadOptions.memoryMap()` | Off-heap buffer; with `load(Path)`, true file-backed mmap. Indexes prewarmed. (default choice) |
| `LoadOptions.onHeap()` | Heap-backed buffer. Indexes prewarmed. |
| `.withPrewarmIndexes(boolean)` | Build indexes eagerly at load (`true`) or lazily. |

**`CompileOptions`** (how the table is compiled):

| Factory / method | Effect |
|---|---|
| `CompileOptions.production(schema)` | Production artifact, policy `AUTO`, priority column `PRIORITY`. |
| `CompileOptions.testInclusive(schema)` | Test-inclusive artifact kind. |
| `.withRuleSelection(RuleSelectionPolicy)` | Override selection policy. |
| `.withPriorityColumn(String)` | Use a different priority column name. |

**`RuleSelectionPolicy`** — `AUTO` (use `PRIORITY` if present, else first-match
row order), `PRIORITY` (lowest priority value wins among matches), `FIRST_MATCH`
(first matching row in CSV order, ignoring `PRIORITY`).

### TEST_ columns

Columns prefixed `TEST_` are validated and stored in **both** artifact kinds
(flagged `0x02`) but are **excluded from evaluation output** — they never appear in
`DecisionOutput.outputs()`. Use them to carry expected values for your own tests
without polluting production results.

## 8. Operators and cell encoding

Operator-row tokens (ALL CAPS), with the aliases the parser also accepts:

| Operator | Alias(es) | Cell form |
|---|---|---|
| `EQ` `NE` | `=` `!=` | scalar |
| `GT` `GTE` `LT` `LTE` | `>` `>=` `<` `<=` | scalar |
| `BETWEEN_INCLUSIVE` | `BETWEEN` | `(min,max)` |
| `BETWEEN_EXCLUSIVE` | | `(min,max)` |
| `NOT_BETWEEN_INCLUSIVE` | `NOT BETWEEN` | `(min,max)` |
| `NOT_BETWEEN_EXCLUSIVE` | | `(min,max)` |
| `IN` | | `(A,B,C)` |
| `NOT_IN` | `NOT IN` | `(A,B,C)` |
| `SET` | | output value |

A blank cell is "no condition." Operands may contain commas; the parser splits on
commas only outside parentheses, so `(APAC,EMEA)` is one set of two values.

Kisoku builds indexes for `EQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, and `NOT_IN`
columns so evaluation prunes candidate rows instead of scanning the whole table.

## Thread-safety

`LoadedRuleset` is immutable and thread-safe — load it once, share it across
threads, and call `evaluate` / `evaluateBulk` concurrently. `DecisionInput`,
`DecisionOutput`, and `BulkResult` are immutable too. Keep `LoadedRuleset`
instances long-lived and `close()` them (via try-with-resources) when done.
