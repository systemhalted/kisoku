package in.systemhalted.kisoku.testutil;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Optional;

/**
 * Utilities for memory measurement in tests.
 *
 * <p>Provides methods for measuring heap and direct buffer usage, forcing garbage collection, and
 * capturing memory snapshots for delta calculations.
 */
public final class MemoryTestUtils {
  private static final MemoryMXBean MEMORY_MX_BEAN = ManagementFactory.getMemoryMXBean();
  private static final int GC_ATTEMPTS = 3;
  private static final long GC_STABILIZATION_MS = 100;

  private MemoryTestUtils() {}

  /**
   * Forces garbage collection and waits for heap to stabilize.
   *
   * <p>Runs GC multiple times with delays between attempts to get a stable heap measurement. This
   * is necessary because a single GC call is only a "suggestion" to the JVM and may not reclaim all
   * eligible objects immediately.
   */
  public static void forceGcAndStabilize() {
    for (int i = 0; i < GC_ATTEMPTS; i++) {
      System.gc();
      try {
        Thread.sleep(GC_STABILIZATION_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  /**
   * Returns the current heap memory usage in bytes.
   *
   * @return heap memory used in bytes
   */
  public static long usedHeapBytes() {
    return MEMORY_MX_BEAN.getHeapMemoryUsage().getUsed();
  }

  /**
   * Returns the current committed heap memory in bytes.
   *
   * @return heap memory committed in bytes
   */
  public static long committedHeapBytes() {
    return MEMORY_MX_BEAN.getHeapMemoryUsage().getCommitted();
  }

  /**
   * Returns the maximum heap memory in bytes.
   *
   * @return maximum heap memory in bytes
   */
  public static long maxHeapBytes() {
    return MEMORY_MX_BEAN.getHeapMemoryUsage().getMax();
  }

  /**
   * Returns the direct buffer pool bean if available.
   *
   * @return optional containing the direct buffer pool bean
   */
  public static Optional<BufferPoolMXBean> directBufferPool() {
    return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
        .filter(pool -> "direct".equals(pool.getName()))
        .findFirst();
  }

  /**
   * Returns the total memory used by direct buffers in bytes.
   *
   * @return direct buffer memory used in bytes, or 0 if unavailable
   */
  public static long directBufferBytes() {
    return directBufferPool().map(BufferPoolMXBean::getMemoryUsed).orElse(0L);
  }

  /**
   * Returns the count of allocated direct buffers.
   *
   * @return direct buffer count, or 0 if unavailable
   */
  public static long directBufferCount() {
    return directBufferPool().map(BufferPoolMXBean::getCount).orElse(0L);
  }

  /**
   * Captures a snapshot of current memory state.
   *
   * @return immutable snapshot of heap and direct buffer state
   */
  public static MemorySnapshot snapshot() {
    return new MemorySnapshot(usedHeapBytes(), directBufferBytes(), directBufferCount());
  }

  /**
   * Captures a stabilized memory snapshot after forcing GC.
   *
   * @return immutable snapshot of heap and direct buffer state after GC
   */
  public static MemorySnapshot stableSnapshot() {
    forceGcAndStabilize();
    return snapshot();
  }

  /**
   * Formats a byte count as a human-readable string.
   *
   * @param bytes the byte count
   * @return formatted string (e.g., "1.5 GB", "256 MB", "1024 KB")
   */
  public static String formatBytes(long bytes) {
    if (bytes < 0) {
      return "-" + formatBytes(-bytes);
    }
    if (bytes >= 1024L * 1024 * 1024) {
      return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    } else if (bytes >= 1024L * 1024) {
      return String.format("%.2f MB", bytes / (1024.0 * 1024));
    } else if (bytes >= 1024) {
      return String.format("%.2f KB", bytes / 1024.0);
    }
    return bytes + " B";
  }

  /**
   * Immutable snapshot of memory state at a point in time.
   *
   * @param heapBytes heap memory used in bytes
   * @param directBytes direct buffer memory used in bytes
   * @param directCount number of direct buffers allocated
   */
  public record MemorySnapshot(long heapBytes, long directBytes, long directCount) {
    /**
     * Calculates the difference from a baseline snapshot.
     *
     * @param baseline the baseline to compare against
     * @return a new snapshot representing the delta
     */
    public MemorySnapshot deltaFrom(MemorySnapshot baseline) {
      return new MemorySnapshot(
          this.heapBytes - baseline.heapBytes,
          this.directBytes - baseline.directBytes,
          this.directCount - baseline.directCount);
    }

    /** Returns a formatted summary of this snapshot. */
    public String format() {
      return String.format(
          "heap=%s, direct=%s, directCount=%d",
          formatBytes(heapBytes), formatBytes(directBytes), directCount);
    }
  }
}
