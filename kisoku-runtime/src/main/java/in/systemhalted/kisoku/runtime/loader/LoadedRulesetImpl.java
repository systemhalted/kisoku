package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.CandidateBitmap;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, thread-safe implementation of LoadedRuleset for evaluation.
 *
 * <p>This class is the core evaluation engine that matches input against rules and produces output.
 */
final class LoadedRulesetImpl implements LoadedRuleset {
  private final RulesetMetadata metadata;
  private final List<ColumnDefinition> columns;
  private final List<ColumnDecoder> decoders;
  private final int[] ruleOrder;
  private final ByteBuffer directBuffer; // For cleanup if memory-mapped

  // Pre-computed indices for evaluation efficiency
  private final int[] inputColumnIndices;
  private final int[] outputColumnIndices;
  private final int ruleIdColumnIndex;

  // Indexed evaluation support
  private final List<ColumnIndex> columnIndexes; // May be null if indexing disabled
  private final long[] allRowsBitmap; // All rows as candidates, or null
  private final StringDictionaryReader dictionary; // For type coercion during indexed eval

  LoadedRulesetImpl(
      RulesetMetadata metadata,
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder,
      ByteBuffer directBuffer,
      List<ColumnIndex> columnIndexes,
      StringDictionaryReader dictionary) {
    this.metadata = metadata;
    this.columns = List.copyOf(columns);
    this.decoders = List.copyOf(decoders);
    this.ruleOrder = ruleOrder.clone();
    this.directBuffer = directBuffer;
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

    // Pre-compute all-rows bitmap for indexed evaluation
    if (columnIndexes != null && !columnIndexes.isEmpty()) {
      this.allRowsBitmap = CandidateBitmap.allOnes(ruleOrder.length);
    } else {
      this.allRowsBitmap = null;
    }
  }

  @Override
  public DecisionOutput evaluate(DecisionInput input) {
    // Use indexed evaluation if indexes are available
    if (columnIndexes != null && allRowsBitmap != null) {
      return evaluateIndexed(input);
    }

    // Fallback to linear scan
    return evaluateLinear(input);
  }

  /**
   * Linear evaluation: O(n) scan through all rules in order.
   *
   * <p>Used when indexes are not available.
   */
  private DecisionOutput evaluateLinear(DecisionInput input) {
    for (int rowIndex : ruleOrder) {
      if (matchesAllInputs(rowIndex, input)) {
        return buildOutput(rowIndex);
      }
    }
    throw new EvaluationException("No matching rule found for input");
  }

  /**
   * Indexed evaluation: filter candidates via bitmap intersection, then verify.
   *
   * <p>Algorithm: 1. Start with all rows as candidates 2. For each indexed column, intersect with
   * column's candidate bitmap 3. Iterate remaining candidates in priority order 4. Verify full
   * match (handles non-indexed columns and blank cells)
   */
  private DecisionOutput evaluateIndexed(DecisionInput input) {
    // Start with all rows as candidates
    long[] candidates = CandidateBitmap.copy(allRowsBitmap);

    // Intersect with each indexed column's candidates
    for (int colIdx : inputColumnIndices) {
      ColumnIndex index = columnIndexes.get(colIdx);
      if (index == null) {
        continue; // Column not indexed, will be verified later
      }

      ColumnDefinition col = columns.get(colIdx);
      if (col.isTestOnly()) {
        continue;
      }

      // Get input value and coerce to comparable int
      Object inputValue = input.get(col.name()).orElse(null);
      int coercedValue = TypeCoercion.toComparableInt(inputValue, col.type(), dictionary);

      // Get candidate rows from index and intersect
      long[] colCandidates = index.getCandidates(coercedValue);
      CandidateBitmap.andInPlace(candidates, colCandidates);

      // Early termination if no candidates remain
      if (CandidateBitmap.isEmpty(candidates)) {
        throw new EvaluationException("No matching rule found for input");
      }
    }

    // Iterate candidates in priority order (ruleOrder) and verify full match
    for (int rowIndex : ruleOrder) {
      if (CandidateBitmap.isSet(candidates, rowIndex)) {
        // Verify all inputs match (handles non-indexed columns)
        if (matchesAllInputs(rowIndex, input)) {
          return buildOutput(rowIndex);
        }
      }
    }

    throw new EvaluationException("No matching rule found for input");
  }

  @Override
  public BulkResult evaluateBulk(DecisionInput base, List<DecisionInput> variants) {
    List<DecisionOutput> results = new ArrayList<>(variants.size());

    for (DecisionInput variant : variants) {
      // Merge base and variant (variant overrides base)
      DecisionInput merged = merge(base, variant);
      DecisionOutput output = evaluate(merged);
      results.add(output);
    }

    return new BulkResult(results);
  }

  private boolean matchesAllInputs(int rowIndex, DecisionInput input) {
    for (int colIdx : inputColumnIndices) {
      ColumnDecoder decoder = decoders.get(colIdx);
      ColumnDefinition col = columns.get(colIdx);

      // Skip TEST_ columns during evaluation
      if (col.isTestOnly()) {
        continue;
      }

      String columnName = col.name();
      Object inputValue = input.get(columnName).orElse(null);

      if (!decoder.matches(rowIndex, inputValue)) {
        return false;
      }
    }
    return true;
  }

  private DecisionOutput buildOutput(int rowIndex) {
    Map<String, Object> outputs = new LinkedHashMap<>();

    for (int colIdx : outputColumnIndices) {
      ColumnDecoder decoder = decoders.get(colIdx);
      ColumnDefinition col = columns.get(colIdx);

      // Skip TEST_ columns in output
      if (col.isTestOnly()) {
        continue;
      }

      Object value = decoder.getValue(rowIndex);
      outputs.put(col.name(), value);
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

    return DecisionOutput.of(ruleId, outputs);
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
    // For memory-mapped buffers, we rely on GC to clean up
    // Direct ByteBuffers created via allocateDirect will be garbage collected
    // No explicit action needed here as we don't use sun.misc.Cleaner
  }
}
