# Testing Guide

This document describes the Kisoku test suite, conventions, and how to run different test categories.

## Test Categories

Tests are organized into categories based on their purpose and resource requirements:

| Category | Tag | Default | Purpose |
|----------|-----|---------|---------|
| Unit | - | Enabled | Fast, isolated tests for individual components |
| Functional | - | Enabled | Integration tests for lifecycle flows |
| Scale | `scale` | Disabled | Large table tests (5M+ rows) |
| Memory | `memory` | Disabled | Heap budget and memory behavior verification |

## Running Tests

### Quick Feedback (Default)

```bash
# Unit and functional tests only (~30 seconds)
mvn test
```

### Full Verification

```bash
# Includes formatting check
mvn verify
```

### Memory Tests

Memory tests verify PRD heap constraints and are gated for performance:

```bash
# Run memory tests
mvn -Dkisoku.runMemoryTests=true test

# Run scale tests (includes memory)
mvn -Dkisoku.runScaleTests=true test

# Customize scale parameters
mvn -Dkisoku.runScaleTests=true \
    -Dkisoku.scaleRows=5000000 \
    -Dkisoku.scaleInputs=60 \
    -Dkisoku.scaleOutputs=20 \
    test
```

### Maximum Scale (20M rows)

Requires significant time and disk space:

```bash
mvn -Dkisoku.runMaxScaleTests=true \
    -Dkisoku.scaleRows=20000000 \
    verify
```

## Test Utilities

### MemoryTestUtils

Location: `kisoku-runtime/src/test/java/in/systemhalted/kisoku/testutil/MemoryTestUtils.java`

Provides memory measurement utilities for verifying heap budgets:

```java
// Force GC and get stable measurement
MemoryTestUtils.forceGcAndStabilize();

// Capture a memory snapshot
MemorySnapshot baseline = MemoryTestUtils.stableSnapshot();

// ... perform operations ...

MemorySnapshot after = MemoryTestUtils.stableSnapshot();
MemorySnapshot delta = after.deltaFrom(baseline);

// Format for logging
System.out.println("Memory delta: " + delta.format());
// Output: "heap=256.00 MB, direct=128.00 MB, directCount=4"
```

#### MemorySnapshot

Immutable record capturing memory state:

| Field | Description |
|-------|-------------|
| `heapBytes` | JVM heap memory used (bytes) |
| `directBytes` | Direct buffer memory used (bytes) |
| `directCount` | Number of allocated direct buffers |

Methods:
- `deltaFrom(baseline)` - Calculate difference from baseline
- `format()` - Human-readable string

### DecisionTableFixtures

Location: `kisoku-runtime/src/test/java/in/systemhalted/kisoku/testutil/DecisionTableFixtures.java`

Generates CSV decision table fixtures for testing:

```java
// Standard fixtures
Path priorityTable = DecisionTableFixtures.writePriorityTable(tempDir);
Schema schema = DecisionTableFixtures.priorityTableSchema();

Path firstMatchTable = DecisionTableFixtures.writeFirstMatchTable(tempDir);
Schema schema = DecisionTableFixtures.firstMatchTableSchema();

// Large table for scale testing
Path largeTable = DecisionTableFixtures.writeLargeTable(
    tempDir,
    5_000_000L,  // rows
    60,          // input columns
    20           // output columns
);
Schema schema = DecisionTableFixtures.largeTableSchema(60, 20);

// Test inputs for evaluation
DecisionInput input = DecisionTableFixtures.createTestInput(seed, inputColumns);
List<DecisionInput> variants = DecisionTableFixtures.createBulkVariants(count, inputColumns);
DecisionInput base = DecisionTableFixtures.createBaseInput(inputColumns);
```

## Memory Test Classes

### HeapBudgetMemoryTest

Verifies the core PRD constraint: heap < 1GB for 5M rows × 80 columns.

```java
@Tag("memory")
@Tag("scale")
@Test
void heapStaysUnder1GBDuringMemoryMappedLoad(@TempDir Path tempDir) {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runMemoryTests"),
        "Set -Dkisoku.runMemoryTests=true to enable");
    // ... test implementation
}
```

### PerEvaluationMemoryTest

Verifies that each `evaluate()` call has bounded allocation, preventing memory growth over many evaluations.

### BulkEvaluationMemoryTest

Verifies that bulk evaluation with 10K variants maintains isolation and bounded memory per variant.

### ConcurrentEvaluationMemoryTest

Verifies thread-safety by running concurrent evaluations from multiple threads without shared mutable state corruption.

### OffHeapMemoryTest

Verifies that `LoadOptions.memoryMap()` allocates direct buffers rather than heap arrays for large tables.

## Test Conventions

### Naming

- Unit tests: `*Test.java`
- Integration tests: `*IT.java`
- Test utilities: `*TestUtils.java`, `*Fixtures.java`

### Tags

Use JUnit 5 tags for test categorization:

```java
@Tag("memory")
@Tag("scale")
@Test
void myScaleTest() { ... }
```

### Assumptions

Gate resource-intensive tests with assumptions:

```java
@Test
void expensiveTest() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("kisoku.runScaleTests"),
        "Set -Dkisoku.runScaleTests=true to enable");
    // ... test
}
```

### Temporary Files

Use JUnit's `@TempDir` for test files:

```java
@Test
void testWithTempDir(@TempDir Path tempDir) throws IOException {
    Path csv = DecisionTableFixtures.writeLargeTable(tempDir, 1000, 10, 5);
    // ... test
}
```

## Surefire Configuration

The Maven Surefire plugin is configured to exclude tagged tests by default:

```xml
<excludedGroups>scale,memory</excludedGroups>
```

Enable via system properties:

| Property | Effect |
|----------|--------|
| `kisoku.runMemoryTests=true` | Enables `memory` tagged tests |
| `kisoku.runScaleTests=true` | Enables `scale` and `memory` tagged tests |
| `kisoku.runMaxScaleTests=true` | Enables 20M row tests |

## Scale Parameters

| Property | Default | Description |
|----------|---------|-------------|
| `kisoku.scaleRows` | 5,000,000 | Number of rows for scale tests |
| `kisoku.scaleInputs` | 60 | Number of input columns |
| `kisoku.scaleOutputs` | 20 | Number of output columns |

## Continuous Integration

Recommended CI configuration:

```yaml
# Fast feedback on every push
test:
  script: mvn test

# Nightly/weekly memory verification
memory-test:
  script: mvn -Dkisoku.runMemoryTests=true test
  schedule: "0 0 * * *"  # Daily

# Release gate with full scale tests
scale-test:
  script: mvn -Dkisoku.runScaleTests=true verify
  when: manual
```
