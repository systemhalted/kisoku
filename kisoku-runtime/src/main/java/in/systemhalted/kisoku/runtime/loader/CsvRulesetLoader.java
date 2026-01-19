package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads compiled decision table artifacts into memory for evaluation.
 *
 * <p>Supports two loading modes:
 *
 * <ul>
 *   <li>On-heap: Copies data into Java arrays for fastest access
 *   <li>Memory-mapped: Uses direct ByteBuffer for lower memory footprint
 * </ul>
 *
 * <p>When {@code prewarmIndexes} is enabled, builds column indexes at load time for faster
 * evaluation.
 */
public final class CsvRulesetLoader implements RulesetLoader {

  @Override
  public LoadedRuleset load(CompiledRuleset compiled, LoadOptions options) {
    byte[] bytes = compiled.bytes();

    if (options.isMemoryMap()) {
      return loadMemoryMapped(bytes, compiled, options);
    } else {
      return loadOnHeap(bytes, compiled, options);
    }
  }

  private LoadedRuleset loadOnHeap(byte[] bytes, CompiledRuleset compiled, LoadOptions options) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    BinaryArtifactReader reader = BinaryArtifactReader.read(buffer);

    // Build indexes if prewarming is enabled
    List<ColumnIndex> indexes = null;
    StringDictionaryReader dictionary = null;
    if (options.isPrewarmIndexes()) {
      indexes = buildIndexes(reader.columns(), reader.decoders(), reader.rowCount());
      dictionary = reader.dictionary();
    }

    return new LoadedRulesetImpl(
        compiled.metadata(),
        reader.columns(),
        reader.decoders(),
        reader.ruleOrder(),
        null, // No direct buffer to clean up
        indexes,
        dictionary);
  }

  private LoadedRuleset loadMemoryMapped(
      byte[] bytes, CompiledRuleset compiled, LoadOptions options) {
    // For true memory-mapping, we'd need the artifact on disk
    // Since CompiledRuleset provides bytes[], we create a direct ByteBuffer
    ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
    direct.put(bytes);
    direct.flip();
    direct.order(ByteOrder.BIG_ENDIAN);

    BinaryArtifactReader reader = BinaryArtifactReader.read(direct);

    // Build indexes if prewarming is enabled
    List<ColumnIndex> indexes = null;
    StringDictionaryReader dictionary = null;
    if (options.isPrewarmIndexes()) {
      indexes = buildIndexes(reader.columns(), reader.decoders(), reader.rowCount());
      dictionary = reader.dictionary();
    }

    return new LoadedRulesetImpl(
        compiled.metadata(),
        reader.columns(),
        reader.decoders(),
        reader.ruleOrder(),
        direct, // Keep reference for potential cleanup
        indexes,
        dictionary);
  }

  /**
   * Build column indexes for all indexable columns.
   *
   * @param columns column definitions
   * @param decoders column decoders
   * @param rowCount total number of rows
   * @return list of indexes (same size as columns, null for non-indexed columns)
   */
  private List<ColumnIndex> buildIndexes(
      List<ColumnDefinition> columns, List<ColumnDecoder> decoders, int rowCount) {
    List<ColumnIndex> indexes = new ArrayList<>(columns.size());

    for (int i = 0; i < columns.size(); i++) {
      ColumnIndex index = ColumnIndexBuilder.build(decoders.get(i), columns.get(i), rowCount);
      indexes.add(index); // May be null for non-indexed columns
    }

    return indexes;
  }
}
