package in.systemhalted.kisoku.api.loading;

/** Options that control how compiled artifacts are loaded. */
public final class LoadOptions {
  private final boolean memoryMap;
  private final boolean prewarmIndexes;
  private final boolean includeTestColumns;

  private LoadOptions(boolean memoryMap, boolean prewarmIndexes, boolean includeTestColumns) {
    this.memoryMap = memoryMap;
    this.prewarmIndexes = prewarmIndexes;
    this.includeTestColumns = includeTestColumns;
  }

  public static LoadOptions memoryMap() {
    return new LoadOptions(true, true, false);
  }

  public static LoadOptions onHeap() {
    return new LoadOptions(false, true, false);
  }

  public LoadOptions withPrewarmIndexes(boolean prewarmIndexes) {
    return new LoadOptions(memoryMap, prewarmIndexes, includeTestColumns);
  }

  /**
   * Includes {@code TEST_}-prefixed columns in evaluation.
   *
   * <p>Test columns are always present in the artifact (flag {@code 0x02}) and excluded from
   * evaluation by default. With this enabled, test input columns participate in matching and test
   * output columns appear in {@code DecisionOutput.outputs()} - the mode for running a table's
   * embedded test expectations against real inputs.
   *
   * @param includeTestColumns whether test columns take part in evaluation
   * @return a copy of these options with the setting applied
   */
  public LoadOptions withIncludeTestColumns(boolean includeTestColumns) {
    return new LoadOptions(memoryMap, prewarmIndexes, includeTestColumns);
  }

  public boolean isMemoryMap() {
    return memoryMap;
  }

  public boolean isPrewarmIndexes() {
    return prewarmIndexes;
  }

  public boolean isIncludeTestColumns() {
    return includeTestColumns;
  }
}
