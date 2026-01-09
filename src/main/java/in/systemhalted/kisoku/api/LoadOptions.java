package in.systemhalted.kisoku.api;

/** Options that control how compiled artifacts are loaded. */
public final class LoadOptions {
  private final boolean memoryMap;
  private final boolean prewarmIndexes;

  private LoadOptions(boolean memoryMap, boolean prewarmIndexes) {
    this.memoryMap = memoryMap;
    this.prewarmIndexes = prewarmIndexes;
  }

  public static LoadOptions memoryMap() {
    return new LoadOptions(true, true);
  }

  public static LoadOptions onHeap() {
    return new LoadOptions(false, true);
  }

  public LoadOptions withPrewarmIndexes(boolean prewarmIndexes) {
    return new LoadOptions(memoryMap, prewarmIndexes);
  }

  public boolean isMemoryMap() {
    return memoryMap;
  }

  public boolean isPrewarmIndexes() {
    return prewarmIndexes;
  }
}
