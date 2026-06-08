package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.runtime.loader.index.CandidateBitmap;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import in.systemhalted.kisoku.runtime.loader.index.ComparisonIndex;
import in.systemhalted.kisoku.runtime.loader.index.EqualityIndex;
import in.systemhalted.kisoku.runtime.loader.index.SetMembershipIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * High-throughput bulk evaluation kernel: scores a columnar batch of inputs against an immutable
 * ruleset (the throughput path of ADR-0010, scalar version).
 *
 * <p>Per input it intersects only the most selective indexed columns into a reusable candidate
 * bitmap (early-exiting once the set is small), then verifies survivors in priority order against
 * all input columns using {@link ColumnDecoder#matchesCoerced}. Results are byte-for-byte identical
 * to {@code LoadedRuleset.evaluate(DecisionInput)} (see {@code ColumnarBulkKernelParityTest}); the
 * selectivity pruning is a pure optimization because verification is exhaustive.
 *
 * <p>Unmatched rows yield {@code null} in the result array (bulk does not abort the batch the way
 * single-eval throws). The kernel holds only immutable ruleset state and per-call scratch, so it is
 * safe to drive from multiple threads.
 */
final class ColumnarBulkKernel {

  /**
   * Default: once the candidate set is this small, stop intersecting and let the priority-ordered
   * verify finish — further bitmap ANDs cost more than checking a handful of survivors directly.
   */
  private static final int DEFAULT_STOP_THRESHOLD = 32;

  private final List<ColumnDefinition> columns;
  private final List<ColumnDecoder> decoders;
  private final int[] ruleOrder;
  private final int[] inputColumnIndices; // slot -> column position
  private final List<ColumnIndex> columnIndexes; // positional with columns, may be null
  private final long[] allRowsBitmap; // may be null when indexing disabled
  private final StringDictionaryReader dictionary;
  private final IntFunction<DecisionOutput> outputBuilder;
  private final int stopThreshold;

  private final int[] intersectionOrder; // input slots that are indexed, most selective first
  private final int bitmapWords;

  ColumnarBulkKernel(
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder,
      int[] inputColumnIndices,
      List<ColumnIndex> columnIndexes,
      long[] allRowsBitmap,
      StringDictionaryReader dictionary,
      IntFunction<DecisionOutput> outputBuilder) {
    this(
        columns,
        decoders,
        ruleOrder,
        inputColumnIndices,
        columnIndexes,
        allRowsBitmap,
        dictionary,
        outputBuilder,
        DEFAULT_STOP_THRESHOLD);
  }

  private ColumnarBulkKernel(
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder,
      int[] inputColumnIndices,
      List<ColumnIndex> columnIndexes,
      long[] allRowsBitmap,
      StringDictionaryReader dictionary,
      IntFunction<DecisionOutput> outputBuilder,
      int stopThreshold) {
    this.columns = columns;
    this.decoders = decoders;
    this.ruleOrder = ruleOrder;
    this.inputColumnIndices = inputColumnIndices;
    this.columnIndexes = columnIndexes;
    this.allRowsBitmap = allRowsBitmap;
    this.dictionary = dictionary;
    this.outputBuilder = outputBuilder;
    this.stopThreshold = stopThreshold;
    this.bitmapWords = CandidateBitmap.longCount(ruleOrder.length);
    this.intersectionOrder = buildIntersectionOrder();
  }

  /**
   * Returns a copy of this kernel with a different early-stop threshold. The result must be
   * identical to single-eval for any threshold (verification is exhaustive); used to test that the
   * pruning heuristic is a pure optimization.
   */
  ColumnarBulkKernel withStopThreshold(int threshold) {
    return new ColumnarBulkKernel(
        columns,
        decoders,
        ruleOrder,
        inputColumnIndices,
        columnIndexes,
        allRowsBitmap,
        dictionary,
        outputBuilder,
        threshold);
  }

  /** Selects indexed input slots and orders them by descending selectivity. */
  private int[] buildIntersectionOrder() {
    if (columnIndexes == null) {
      return new int[0];
    }
    List<Integer> slots = new ArrayList<>();
    for (int k = 0; k < inputColumnIndices.length; k++) {
      if (columnIndexes.get(inputColumnIndices[k]) != null) {
        slots.add(k);
      }
    }
    slots.sort((a, b) -> Integer.compare(selectivity(b), selectivity(a)));
    int[] order = new int[slots.size()];
    for (int i = 0; i < order.length; i++) {
      order[i] = slots.get(i);
    }
    return order;
  }

  private int selectivity(int slot) {
    ColumnIndex index = columnIndexes.get(inputColumnIndices[slot]);
    if (index instanceof EqualityIndex e) {
      return e.uniqueValueCount();
    }
    if (index instanceof SetMembershipIndex s) {
      return s.uniqueValueCount();
    }
    if (index instanceof ComparisonIndex c) {
      return c.uniqueValueCount();
    }
    return 0;
  }

  /** Encodes raw inputs into a columnar batch of pre-coerced codes (the in-memory input source). */
  InputBatch encode(List<DecisionInput> inputs) {
    int rows = inputs.size();
    int slots = inputColumnIndices.length;
    int[][] codes = new int[slots][rows];
    for (int k = 0; k < slots; k++) {
      ColumnDefinition col = columns.get(inputColumnIndices[k]);
      int[] column = codes[k];
      for (int row = 0; row < rows; row++) {
        Object value = inputs.get(row).get(col.name()).orElse(null);
        column[row] = TypeCoercion.toComparableInt(value, col.type(), dictionary);
      }
    }
    return new InputBatch(codes, rows);
  }

  /** Sequentially evaluates every row in the batch. */
  DecisionOutput[] evaluate(InputBatch batch) {
    DecisionOutput[] results = new DecisionOutput[batch.rowCount()];
    long[] scratch = allRowsBitmap != null ? new long[bitmapWords] : null;
    for (int row = 0; row < results.length; row++) {
      results[row] = evaluateRow(batch, row, scratch);
    }
    return results;
  }

  /**
   * Evaluates the batch across the caller-supplied executor by partitioning rows into disjoint
   * chunks, each with its own scratch bitmap, writing into disjoint slices of the result array.
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
              long[] scratch = allRowsBitmap != null ? new long[bitmapWords] : null;
              for (int row = start; row < end; row++) {
                results[row] = evaluateRow(batch, row, scratch);
              }
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

  /** Returns the output for a single row, or {@code null} if no rule matches. */
  private DecisionOutput evaluateRow(InputBatch batch, int row, long[] scratch) {
    if (allRowsBitmap == null || intersectionOrder.length == 0) {
      // No usable indexes: verify all rules in priority order.
      for (int rowIndex : ruleOrder) {
        if (matchesAllInputs(batch, row, rowIndex)) {
          return outputBuilder.apply(rowIndex);
        }
      }
      return null;
    }

    System.arraycopy(allRowsBitmap, 0, scratch, 0, scratch.length);
    for (int slot : intersectionOrder) {
      ColumnIndex index = columnIndexes.get(inputColumnIndices[slot]);
      CandidateBitmap.andInPlace(scratch, index.getCandidates(batch.code(slot, row)));
      if (CandidateBitmap.isEmpty(scratch)) {
        return null;
      }
      if (CandidateBitmap.cardinality(scratch) <= stopThreshold) {
        break;
      }
    }

    for (int rowIndex : ruleOrder) {
      if (CandidateBitmap.isSet(scratch, rowIndex) && matchesAllInputs(batch, row, rowIndex)) {
        return outputBuilder.apply(rowIndex);
      }
    }
    return null;
  }

  private boolean matchesAllInputs(InputBatch batch, int row, int rowIndex) {
    for (int k = 0; k < inputColumnIndices.length; k++) {
      int colIdx = inputColumnIndices[k];
      if (columns.get(colIdx).isTestOnly()) {
        continue;
      }
      if (!decoders.get(colIdx).matchesCoerced(rowIndex, batch.code(k, row))) {
        return false;
      }
    }
    return true;
  }
}
