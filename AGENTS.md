# Repository Guidelines

## Project Structure & Module Organization
See `PRD.md` for requirements and keep it in sync with changes. For a Java + Maven layout, use:
- `src/main/java/` engine/runtime code
- `src/test/java/` unit/integration tests
- `src/test/resources/` fixtures and datasets
- `docs/` architecture or design notes
- `scripts/` build/dev helper scripts
- `examples/` decision table samples (CSV/JSON)

## Build, Test, and Development Commands
Use these Maven entry points:
- `mvn -q -DskipTests package` compile and package
- `mvn test` run unit tests
- `mvn verify` run unit + integration tests and formatting check
- `mvn spotless:apply` format code (runs google-java-format)
- `mvn -DskipTests exec:java` run a local runner or benchmark (after adding the exec plugin)

## Coding Style & Naming Conventions
Use Java conventions:
- Indent 4 spaces for code, 2 for YAML/JSON; use LF line endings.
- Naming: `kebab-case` for docs/scripts, `UpperCamelCase` for types, `lowerCamelCase` for methods/fields, `UPPER_SNAKE_CASE` for constants.
- Keep one top-level public class per file; avoid wildcard imports.
- Use Spotless with google-java-format; run `mvn spotless:apply` before pushing.

## Testing Guidelines
Use JUnit 5 with Maven Surefire and Failsafe.
- Name unit tests `*Test` under `src/test/java/`; integration tests `*IT` under `src/test/java/`.
- Keep fixtures in `src/test/resources/` and use small representative decision tables.
- Separate performance/scale tests from unit tests and mark them clearly.
- Keep tests deterministic; avoid time- or randomness-dependent assertions.

## Commit & Pull Request Guidelines
There is no git history in this workspace, so no established convention. Use Conventional Commits (e.g., `feat:`, `fix:`, `perf:`).
- PRs should link relevant requirements in `PRD.md` and summarize behavior changes.
- Include test evidence and benchmark results when touching compilation, indexing, or evaluation paths.
- Keep PRs small and update this file when adding new commands or structure.

## Performance & Architecture Constraints
Design choices must honor PRD constraints: compiled decision tables, indexed evaluation, bulk mode, immutability, and low heap usage. Avoid per-row/per-cell heap objects; prefer compact or off-heap representations and keep evaluation state isolated per request.
