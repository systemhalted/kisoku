package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.evaluation.MatchDiagnostics;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.PostingListIndex;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Immutable, thread-safe implementation of LoadedRuleset for evaluation.
 *
 * <p>This class is the core evaluation engine that matches input against rules and produces output.
 */
final class LoadedRulesetImpl implements LoadedRuleset {
  private final RulesetMetadata metadata;
  private final List<ColumnDefinition> columns;
  private final List<ColumnDecoder> decoders;
  private final int[] ruleOrder; // null = identity permutation (the compiler's normal output)
  private final int rowCount;
  private final ByteBuffer directBuffer; // For cleanup if memory-mapped
  private final AutoCloseable resource; // Backing file channel for mapped loads, or null

  // Pre-computed indices for evaluation efficiency
  private final int[] inputColumnIndices;
  private final int[] outputColumnIndices;
  private final int ruleIdColumnIndex;

  // Indexed evaluation support
  private final List<PostingListIndex> columnIndexes; // May be null if indexing disabled
  private final IndexedMatcher matcher;
  private final ColumnarBulkKernel kernel;
  private final boolean includeTestColumns;
  private final StringDictionaryReader dictionary; // For input coercion

  LoadedRulesetImpl(
      RulesetMetadata metadata,
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder,
      int rowCount,
      ByteBuffer directBuffer,
      List<PostingListIndex> columnIndexes,
      StringDictionaryReader dictionary) {
    this(
        metadata,
        columns,
        decoders,
        ruleOrder,
        rowCount,
        directBuffer,
        null,
        columnIndexes,
        dictionary,
        false);
  }

  LoadedRulesetImpl(
      RulesetMetadata metadata,
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder,
      int rowCount,
      ByteBuffer directBuffer,
      AutoCloseable resource,
      List<PostingListIndex> columnIndexes,
      StringDictionaryReader dictionary,
      boolean includeTestColumns) {
    this.metadata = metadata;
    this.columns = List.copyOf(columns);
    this.decoders = List.copyOf(decoders);
    this.ruleOrder = ruleOrder != null ? ruleOrder.clone() : null;
    this.rowCount = rowCount;
    this.directBuffer = directBuffer;
    this.resource = resource;
    // Use unmodifiableList since columnIndexes may contain nulls (non-indexed columns)
    this.columnIndexes =
        columnIndexes != null ? Collections.unmodifiableList(new ArrayList<>(columnIndexes)) : null;
    this.dictionary = dictionary;

    // Pre-compute column indices
    List<Integer> inputIndices = new ArrayList<>();
    List<Integer> outputIndices = new ArrayList<>();
    int ruleIdIdx = -1;

    for (int i = 0; i < columns.size(); i++) {
      ColumnDefinition col = columns.get(i);
      if (col.operator() == Operator.RULE_ID) {
        ruleIdIdx = i;
      } else if (col.isInput()) {
        inputIndices.add(i);
      } else if (col.isOutput()) {
        outputIndices.add(i);
      }
    }

    this.inputColumnIndices = inputIndices.stream().mapToInt(Integer::intValue).toArray();
    this.outputColumnIndices = outputIndices.stream().mapToInt(Integer::intValue).toArray();
    this.ruleIdColumnIndex = ruleIdIdx;

    this.includeTestColumns = includeTestColumns;
    this.matcher =
        new IndexedMatcher(
            this.columns,
            this.decoders,
            this.columnIndexes,
            this.inputColumnIndices,
            this.ruleOrder,
            rowCount,
            includeTestColumns);
    this.kernel =
        new ColumnarBulkKernel(
            this.columns,
            this.inputColumnIndices,
            this.matcher,
            this.dictionary,
            this::buildOutput);
  }

  @Override
  public DecisionOutput evaluate(DecisionInput input) {
    int rowIndex = findMatchingRow(input);
    if (rowIndex < 0) {
      throw new EvaluationException("No matching rule found for input");
    }
    return buildOutput(rowIndex);
  }

  @Override
  public DecisionOutput explain(DecisionInput input) {
    int rowIndex = findMatchingRow(input);
    if (rowIndex < 0) {
      throw new EvaluationException("No matching rule found for input");
    }
    return buildOutput(rowIndex, buildDiagnostics(rowIndex));
  }

  /**
   * Renders the winning rule's non-blank input conditions as "NAME OPERATOR operand" lines.
   *
   * <p>Only input columns appear - a condition is something the input had to satisfy - and test
   * columns follow the ruleset's inclusion setting, so diagnostics always describe exactly the
   * conditions that were enforced.
   */
  private MatchDiagnostics buildDiagnostics(int rowIndex) {
    List<String> conditions = new ArrayList<>();
    for (int colIdx : inputColumnIndices) {
      ColumnDefinition col = columns.get(colIdx);
      if (!includeTestColumns && col.isTestOnly()) {
        continue;
      }
      String operand = decoders.get(colIdx).describeOperand(rowIndex);
      if (operand != null) {
        conditions.add(col.name() + " " + col.operator() + " " + operand);
      }
    }
    return new MatchDiagnostics(conditions);
  }

  /**
   * Finds the winning rule row for an input.
   *
   * <p>Coerces every input column once into the comparable-code domain, then delegates to the
   * {@link IndexedMatcher}, which drives matching from the most selective indexed column (or a
   * linear scan when no index helps).
   *
   * @param input the evaluation input
   * @return the physical row index of the first matching rule in evaluation order, or -1 if no rule
   *     matches
   */
  private int findMatchingRow(DecisionInput input) {
    long[] codes = new long[inputColumnIndices.length];
    boolean[] present = new boolean[inputColumnIndices.length];

    // Coerce every input slot; whether a test column takes part in matching is the matcher's
    // decision (testOnlySlot), not the coercion's.
    for (int k = 0; k < inputColumnIndices.length; k++) {
      ColumnDefinition col = columns.get(inputColumnIndices[k]);
      Object value = input.get(col.name()).orElse(null);
      present[k] = value != null;
      codes[k] = TypeCoercion.toComparableCode(value, col.type(), col.scale(), dictionary);
    }

    return matcher.findFirstMatch(codes, present);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Runs on the columnar bulk kernel: inputs are coerced once into a columnar batch, then each
   * row is resolved by the same matcher as single evaluation. Each variant is independent: one that
   * matches no rule contributes an empty result rather than failing the batch.
   */
  @Override
  public BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants) {
    InputBatch batch = kernel.encode(mergeAll(base, variants));
    return new BulkResult(Arrays.asList(kernel.evaluate(batch)));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Partitions the batch into disjoint chunks on the caller's executor; results are identical to
   * the sequential form.
   */
  @Override
  public BulkResult evaluateBulk(
      DecisionInput base, List<DecisionInput> variants, Executor executor, int parallelism) {
    InputBatch batch = kernel.encode(mergeAll(base, variants));
    return new BulkResult(Arrays.asList(kernel.evaluate(batch, executor, parallelism)));
  }

  /** Overlays each variant on the base input (variant fields win). */
  private List<DecisionInput> mergeAll(DecisionInput base, List<DecisionInput> variants) {
    List<DecisionInput> merged = new ArrayList<>(variants.size());
    for (DecisionInput variant : variants) {
      merged.add(merge(base, variant));
    }
    return merged;
  }

  /** The columnar bulk kernel backing {@code evaluateBulk}, exposed for tests. */
  ColumnarBulkKernel bulkKernel() {
    return kernel;
  }

  private DecisionOutput buildOutput(int rowIndex) {
    return buildOutput(rowIndex, null);
  }

  private DecisionOutput buildOutput(int rowIndex, MatchDiagnostics diagnostics) {
    Map<String, Object> outputs = new LinkedHashMap<>();

    for (int colIdx : outputColumnIndices) {
      ColumnDecoder decoder = decoders.get(colIdx);
      ColumnDefinition col = columns.get(colIdx);

      // TEST_ output columns surface only when test columns are included
      if (!includeTestColumns && col.isTestOnly()) {
        continue;
      }

      // A blank output cell has no value; omit the key rather than mapping it to null.
      Object value = decoder.getValue(rowIndex);
      if (value != null) {
        outputs.put(col.name(), value);
      }
    }

    // Get RULE_ID
    String ruleId = null;
    if (ruleIdColumnIndex >= 0) {
      ColumnDecoder ruleIdDecoder = decoders.get(ruleIdColumnIndex);
      Object ruleIdValue = ruleIdDecoder.getValue(rowIndex);
      ruleId = ruleIdValue != null ? ruleIdValue.toString() : null;
    }

    if (ruleId == null) {
      throw new EvaluationException("Rule at row " + rowIndex + " has no RULE_ID");
    }

    return diagnostics == null
        ? DecisionOutput.of(ruleId, outputs)
        : DecisionOutput.of(ruleId, outputs, diagnostics);
  }

  private DecisionInput merge(DecisionInput base, DecisionInput variant) {
    Map<String, Object> merged = new LinkedHashMap<>(base.values());
    merged.putAll(variant.values());
    return DecisionInput.of(merged);
  }

  @Override
  public RulesetMetadata metadata() {
    return metadata;
  }

  @Override
  public void close() {
    // Close the backing file channel for memory-mapped loads. The mapping itself has no public
    // unmap; we drop our references and let the Cleaner associated with the mapped buffer release
    // it on GC (we deliberately avoid sun.misc.Unsafe.invokeCleaner, which is hostile to JPMS).
    // FileChannel.close() is idempotent, so calling close() twice is safe.
    if (resource != null) {
      try {
        resource.close();
      } catch (Exception e) {
        // Best-effort cleanup; nothing actionable if the channel fails to close.
      }
    }
  }
}
