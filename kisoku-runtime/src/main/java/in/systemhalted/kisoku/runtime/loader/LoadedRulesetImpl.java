package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.evaluation.BulkResult;
import in.systemhalted.kisoku.api.evaluation.DecisionInput;
import in.systemhalted.kisoku.api.evaluation.DecisionOutput;
import in.systemhalted.kisoku.api.evaluation.EvaluationException;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.runtime.csv.Operator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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

  LoadedRulesetImpl(
      RulesetMetadata metadata,
      List<ColumnDefinition> columns,
      List<ColumnDecoder> decoders,
      int[] ruleOrder,
      ByteBuffer directBuffer) {
    this.metadata = metadata;
    this.columns = List.copyOf(columns);
    this.decoders = List.copyOf(decoders);
    this.ruleOrder = ruleOrder.clone();
    this.directBuffer = directBuffer;

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
  }

  @Override
  public DecisionOutput evaluate(DecisionInput input) {
    // Iterate rules in order (pre-sorted by priority if applicable)
    for (int rowIndex : ruleOrder) {
      if (matchesAllInputs(rowIndex, input)) {
        return buildOutput(rowIndex);
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
