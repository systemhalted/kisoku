# ADR-0001: Multi-Module API/Runtime Split

## Status

Accepted

## Context

Kisoku is a decision-table rule engine with distinct lifecycle phases: validate,
compile, load, and evaluate. The design must balance simplicity for typical use
cases against extensibility for advanced scenarios.

Key drivers for this decision:

1. **PRD Requirements**: Support for large tables (up to 20M rows) with multiple
   loading strategies (heap, memory-mapped, off-heap).

2. **Encapsulation**: Implementation details like columnar storage formats, index
   structures, and evaluation algorithms should not leak into the public API.

3. **Extensibility**: Future requirements may include:
    - Additional input formats (Excel, Parquet)
    - Alternative evaluation strategies (JIT compilation, AOT compilation)
    - Different memory backends (Apache Arrow for Python/R interop)
    - Non-JVM targets (WebAssembly for browser-based evaluation)

4. **Industry Patterns**: Established Java libraries (JDBC, JPA, SLF4J, Servlet)
   separate API from implementation to enable pluggable providers.

## Decision

Split Kisoku into two JPMS modules:

### kisoku-api (Open Module)

- Contains all public interfaces, types, and exceptions
- Exports all packages for client consumption
- Defines service interfaces: `RulesetValidator`, `RulesetCompiler`, `RulesetLoader`
- Provides the `Kisoku` entry point that uses `ServiceLoader` to discover implementations

### kisoku-runtime (Closed Module)

- Contains all implementation classes
- Exports nothing (fully encapsulated)
- Provides implementations via `META-INF/services/` descriptors
- Can be replaced entirely with an alternative runtime

### ServiceLoader Integration

```java
// Client code - no direct dependency on kisoku-runtime
Schema schema = Schema.builder()
                .column("AGE", ColumnType.INTEGER)
                .column("DISCOUNT", ColumnType.DECIMAL)
                .build();

// ServiceLoader discovers DefaultRulesetCompiler from kisoku-runtime
CompiledRuleset compiled = Kisoku.compiler()
        .compile(source, CompileOptions.production(schema));
```

## Alternatives Considered

### Alternative 1: Single-module approach with package-private encapsulation

The package-private access is only enforced at compile time and during normal runtime access. However, the `Reflection 
API` can bypass these checks

```java
// Even for package-private classes/members:
Field field = someObject.getClass().getDeclaredField("privateField");
field.setAccessible(true);  // Bypasses access control
Object value = field.get(someObject);
```

Any code can call `setAccessible(true)` on any member, regardless of its declared visibility making it an "escape hatch"
making Java's access modifiers advisory rather than enforced. Package-private provided no real protection against
determined access.

This is not what we needed. We wanted to keep internal implementation safe and allow access only through the public API.
JPMS was specifically designed to close this reflection loophole. What makes JPMS different is that it adds a second
layer
of protection: even if you call `setAccessible(true)`, the module system will throw InaccessibleObjectException unless
the module explicitly `opens` that package.

**Why not single-module JPMS with unexported packages?**

A single JPMS module *can* protect internal packages from external access by simply not exporting them:

```java
module kisoku {
    exports in.systemhalted.kisoku.api;
    // runtime packages not exported - protected even from reflection
}
```

However, multi-module provides benefits beyond external protection:

| Concern                           | Single Module                 | Multi-Module                   |
|-----------------------------------|-------------------------------|--------------------------------|
| Protect internals from users      | ✅ Works                       | ✅ Works                        |
| Swap implementations (JDBC-style) | ❌ Can't replace runtime       | ✅ Drop-in replacement          |
| Independent versioning            | ❌ One version for all         | ✅ API stable, runtime evolves  |
| Compile-time separation           | ❌ API code can import runtime | ✅ Physically impossible        |
| Minimal dependencies              | ❌ Pull everything             | ✅ Depend only on API for mocks |

The key driver is **swappable implementations**: future runtimes like `kisoku-runtime-arrow` or
`kisoku-runtime-wasm` can provide the same service interfaces without modifying the core.

### Alternative 2: Separate modules per lifecycle phase (kisoku-validator, kisoku-compiler, etc.)

Lifecycle phases in themselves are not truly independent. The lifecycle phases (validate →
compile → load → evaluate) form a cohesive pipeline where:

- A compiler change often requires validator changes (new operators, formats)
- A loader change often requires compiler changes (new artifact format)

Currently, validation and compilation are part of runtime module and if an alternative implementation is provided in
the future, they need to be handled together. Over-modularization would create artificial boundaries that force
coordinated releases anyway — negating the benefit of separate modules.

Per-phase modules would add accidental complexity (build coordination, version matrices) without reducing essential
complexity (the phases are inherently coupled by data flow).

### Alternative 3: Interface-only API with factory methods instead of ServiceLoader

Static factory methods like `KisokuFactory.createCompiler()` were considered but rejected in favor of `ServiceLoader`:

**Coupling**: Factory methods require `kisoku-api` to directly `import` implementation classes from `kisoku-runtime`,
breaking the clean module separation. The API module would need a compile-time dependency on the runtime module,
defeating the purpose of the split.

**Swapping implementations**: With factories, switching from `kisoku-runtime` to `kisoku-runtime-arrow` would require
code changes or complex conditional logic. ServiceLoader allows users to simply swap the JAR on the classpath — the
binding happens at runtime without recompilation.

**Established patterns**: Industry-standard libraries chose ServiceLoader for this exact reason:
- JDBC: `DriverManager` discovers drivers via ServiceLoader
- SLF4J: Logging backend binds at runtime via ServiceLoader
- JPA: `Persistence.createEntityManagerFactory()` uses ServiceLoader to find providers

**Testing**: ServiceLoader makes mocking easier — tests can provide a mock implementation via `META-INF/services/`
without modifying production code. Factory methods would require dependency injection frameworks or test-specific
factory subclasses.

## Consequences

### Positive

- **True Encapsulation**: JPMS enforces that `kisoku-runtime` internals cannot be
  accessed, even via reflection (unless explicitly opened).

- **Plugin Architecture**: Alternative runtimes can be provided as drop-in
  replacements. For example:

  | Runtime Type | Example | Use Case |
  |--------------|---------|----------|
  | Input Format | `ExcelRulesetCompiler` | Business users maintain rules in spreadsheets |
  | Input Format | `ParquetRulesetCompiler` | Rules from data pipelines |
  | Eval Strategy | `JitCompiledLoader` | Bytecode generation for faster evaluation |
  | Eval Strategy | `AotCompiledLoader` | Pre-compiled for fastest startup |
  | Memory | `ArrowLoader` | Zero-copy interop with Python/R |
  | Target | `WasmCompiler` | Browser-based rule evaluation |

- **Independent Versioning**: The API can remain stable while the runtime evolves.
  Clients depend only on `kisoku-api`, allowing runtime upgrades without recompilation.

- **Follows Established Patterns**: Developers familiar with JDBC, SLF4J, or JPA
  will recognize this structure immediately.

### Negative

- **Build Complexity**: Two Maven modules with separate `pom.xml` files and a
  parent aggregator. Module dependencies must be managed carefully.

- **ServiceLoader Ceremony**: Simple use cases still require the ServiceLoader
  discovery mechanism, though this is hidden behind `Kisoku.compiler()` etc.

- **Maintenance Overhead**: Two `module-info.java` files to maintain. Changes to
  service interfaces require coordinated updates.

### Neutral

- **Future Runtimes**: Additional runtimes (e.g., `kisoku-runtime-arrow`) can be
  added as separate Maven artifacts without modifying existing modules.

- **Testing**: Integration tests can use the real runtime via ServiceLoader, while
  unit tests can mock the service interfaces directly.

## References

- [JPMS Module System](https://openjdk.org/projects/jigsaw/spec/sotms/)
- [ServiceLoader Pattern](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/ServiceLoader.html)
- Related: `docs/architecture.md` for implementation details
