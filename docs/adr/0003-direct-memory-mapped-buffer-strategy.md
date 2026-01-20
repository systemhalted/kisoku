# ADR-0003: Direct/Memory-Mapped Buffer Strategy

## Status

Accepted

## Context

Kisoku must load compiled artifacts containing up to 20M rows while keeping JVM heap usage under 1GB. The loading strategy determines:

1. **Memory Model**: Whether rule data resides on JVM heap, off-heap (direct buffers), or OS-managed memory-mapped files.

2. **Startup Latency**: Memory-mapped files can be accessed immediately; heap loading requires reading the entire file first.

3. **Memory Pressure**: Large heap allocations trigger GC pauses; off-heap memory avoids this but requires manual lifecycle management.

4. **Sharing Across Processes**: Memory-mapped files can be shared by multiple JVM processes, reducing total system memory.

The `LoadOptions` API exposes two strategies: `memoryMap()` (default) and `onHeap()`.

## Decision

<!-- TODO(human): Describe the decision in detail.
     Key points to cover:
     - Why memory-mapped is the default strategy
     - When onHeap() is preferred (small tables, testing)
     - The prewarmIndexes option and its purpose
     - How ByteBuffer abstraction allows both strategies to share evaluation code
-->

### Loading Strategies

```java
// Memory-mapped (default) - OS manages paging
LoadOptions.memoryMap()

// On-heap - full copy into JVM heap
LoadOptions.onHeap()
```

### Implementation Details

<!-- TODO(human): Describe how BinaryArtifactReader and LoadedRulesetImpl
     handle the different buffer types -->

## Alternatives Considered

<!-- TODO(human): Document alternatives such as:
     1. Off-heap direct ByteBuffers (Unsafe allocation)
     2. Apache Arrow memory management
     3. Custom memory-mapped file wrapper
-->

## Consequences

### Positive

<!-- TODO(human): List benefits like:
     - Sub-1GB heap for 20M row tables
     - Fast startup with lazy loading
     - OS-level page caching
-->

### Negative

<!-- TODO(human): List drawbacks like:
     - Memory-mapped files require careful resource cleanup
     - Page faults on first access
     - Platform-specific behavior
-->

### Neutral

- Both strategies use the same `ByteBuffer` interface, so evaluation code is unchanged.
- The `prewarmIndexes` option can be combined with either strategy.

## References

- `LoadOptions.java` - Strategy configuration API
- `BinaryArtifactReader.java` - Artifact parsing implementation
- `LoadedRulesetImpl.java` - Runtime evaluation with buffer access
- Related: ADR-0002 (Columnar Storage) for data format
