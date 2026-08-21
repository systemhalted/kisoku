package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * High-throughput bulk evaluation kernel: scores a columnar batch of inputs against an immutable
 * ruleset (the throughput path of ADR-0010, scalar version).
 *
 * <p>Inputs are coerced once into a columnar {@link InputBatch}; each row is then resolved by the
 * shared {@link IndexedMatcher} - the same selectivity-driven candidate enumeration and exhaustive
 * verification as single evaluation, so results are identical by construction (see {@code
 * ColumnarBulkKernelParityTest}).
 *
 * <p>Unmatched rows yield {@code null} in the result array (bulk does not abort the batch the way
 * single-eval throws). The kernel holds only immutable ruleset state and per-call scratch, so it is
 * safe to drive from multiple threads.
 */
final class ColumnarBulkKernel {

  private final List<ColumnDefinition> columns;
  private final int[] inputColumnIndices; // slot -> column position
  private final IndexedMatcher matcher;
  private final StringDictionaryReader dictionary;
  private final IntFunction<DecisionOutput> outputBuilder;

  ColumnarBulkKernel(
      List<ColumnDefinition> columns,
      int[] inputColumnIndices,
      IndexedMatcher matcher,
      StringDictionaryReader dictionary,
      IntFunction<DecisionOutput> outputBuilder) {
    this.columns = columns;
    this.inputColumnIndices = inputColumnIndices;
    this.matcher = matcher;
    this.dictionary = dictionary;
    this.outputBuilder = outputBuilder;
  }

  /** Encodes raw inputs into a columnar batch of pre-coerced codes (the in-memory input source). */
  InputBatch encode(List<DecisionInput> inputs) {
    int rows = inputs.size();
    int slots = inputColumnIndices.length;
    long[][] codes = new long[slots][rows];
    boolean[][] present = new boolean[slots][rows];
    for (int k = 0; k < slots; k++) {
      ColumnDefinition col = columns.get(inputColumnIndices[k]);
      long[] column = codes[k];
      boolean[] supplied = present[k];
      for (int row = 0; row < rows; row++) {
        Object value = inputs.get(row).get(col.name()).orElse(null);
        supplied[row] = value != null;
        column[row] = TypeCoercion.toComparableCode(value, col.type(), col.scale(), dictionary);
      }
    }
    return new InputBatch(codes, present, rows);
  }

  /** Sequentially evaluates every row in the batch. */
  DecisionOutput[] evaluate(InputBatch batch) {
    DecisionOutput[] results = new DecisionOutput[batch.rowCount()];
    evaluateRange(batch, 0, results.length, results);
    return results;
  }

  /**
   * Evaluates the batch across the caller-supplied executor by partitioning rows into disjoint
   * chunks, each with its own scratch arrays, writing into disjoint slices of the result array.
   */
  DecisionOutput[] evaluate(InputBatch batch, Executor executor, int parallelism) {
    int rows = batch.rowCount();
    if (parallelism <= 1 || rows <= 1) {
      return evaluate(batch);
    }
    DecisionOutput[] results = new DecisionOutput[rows];
    int chunks = Math.min(parallelism, rows);
    int chunkSize = (rows + chunks - 1) / chunks;

    CountDownLatch latch = new CountDownLatch(chunks);
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    for (int c = 0; c < chunks; c++) {
      int start = c * chunkSize;
      int end = Math.min(start + chunkSize, rows);
      executor.execute(
          () -> {
            try {
              evaluateRange(batch, start, end, results);
            } catch (RuntimeException e) {
              failure.compareAndSet(null, e);
            } finally {
              latch.countDown();
            }
          });
    }
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Bulk evaluation interrupted", e);
    }
    RuntimeException e = failure.get();
    if (e != null) {
      throw e;
    }
    return results;
  }

  /** Evaluates rows [start, end), reusing one pair of per-row scratch arrays for the range. */
  private void evaluateRange(InputBatch batch, int start, int end, DecisionOutput[] results) {
    int slots = inputColumnIndices.length;
    long[] codes = new long[slots];
    boolean[] present = new boolean[slots];
    for (int row = start; row < end; row++) {
      for (int k = 0; k < slots; k++) {
        codes[k] = batch.code(k, row);
        present[k] = batch.present(k, row);
      }
      int match = matcher.findFirstMatch(codes, present);
      results[row] = match >= 0 ? outputBuilder.apply(match) : null;
    }
  }
}
