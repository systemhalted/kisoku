package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.loading.LoadException;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import in.systemhalted.kisoku.runtime.compiler.CompiledRulesetImpl;
import in.systemhalted.kisoku.runtime.csv.Operator;
import in.systemhalted.kisoku.runtime.loader.index.ColumnIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads compiled decision table artifacts for evaluation.
 *
 * <p>Compiled rulesets are file-backed, so both {@code load(CompiledRuleset)} and {@code
 * load(Path)} resolve to the same file paths:
 *
 * <ul>
 *   <li>Memory-mapped (default): the artifact is mapped section by section - metadata once, each
 *       column's data as its own mapping - so no mapping spans 2 GB and artifacts of any size load
 *       without copying onto the heap.
 *   <li>On-heap: the whole file is read into one array; only possible for artifacts under 2 GB.
 * </ul>
 *
 * <p>When {@code prewarmIndexes} is enabled (the default), per-column posting-list indexes are
 * built at load time into direct buffers.
 */
public final class CsvRulesetLoader implements RulesetLoader {

  @Override
  public LoadedRuleset load(CompiledRuleset compiled, LoadOptions options) {
    try {
      if (compiled instanceof CompiledRulesetImpl fileBacked) {
        return loadFromFile(fileBacked.artifactPath(), options, compiled.metadata());
      }
      // Foreign implementation: all we have is its byte form.
      ByteBuffer buffer = ByteBuffer.wrap(compiled.bytes()).order(ByteOrder.BIG_ENDIAN);
      return buildFromBuffer(buffer, options, compiled.metadata());
    } catch (IOException e) {
      throw new LoadException("Failed to load compiled artifact: " + e.getMessage(), e);
    }
  }

  @Override
  public LoadedRuleset load(Path artifact, LoadOptions options) throws IOException {
    return loadFromFile(artifact, options, null);
  }

  private LoadedRuleset loadFromFile(Path artifact, LoadOptions options, RulesetMetadata metadata)
      throws IOException {
    if (!options.isMemoryMap()) {
      long size = Files.size(artifact);
      if (size > Integer.MAX_VALUE - 8) {
        throw new LoadException(
            "Artifact is "
                + size
                + " bytes; on-heap loading is limited to 2GB - use"
                + " LoadOptions.memoryMap()");
      }
      ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(artifact)).order(ByteOrder.BIG_ENDIAN);
      return buildFromBuffer(buffer, options, metadata);
    }

    // Memory-mapped: per-section mappings through one channel, kept open for cleanup.
    FileChannel channel = FileChannel.open(artifact, StandardOpenOption.READ);
    try {
      BinaryArtifactReader reader = BinaryArtifactReader.readMapped(channel);
      return build(reader, channel, options, metadata);
    } catch (RuntimeException | IOException e) {
      channel.close();
      throw e;
    }
  }

  /** Builds a ruleset from an artifact wholly contained in one heap buffer. */
  private LoadedRuleset buildFromBuffer(
      ByteBuffer buffer, LoadOptions options, RulesetMetadata metadata) {
    return build(BinaryArtifactReader.read(buffer), null, options, metadata);
  }

  private LoadedRuleset build(
      BinaryArtifactReader reader,
      AutoCloseable resource,
      LoadOptions options,
      RulesetMetadata metadata) {
    StringDictionaryReader dictionary = reader.dictionary();
    List<ColumnIndex> indexes = null;
    if (options.isPrewarmIndexes()) {
      // Prefer indexes persisted in the artifact (mapped, no build cost); fall back to building.
      indexes = reader.persistedIndexes();
      if (indexes == null) {
        indexes = buildIndexes(reader.columns(), reader.decoders(), reader.rowCount());
      }
    }

    return new LoadedRulesetImpl(
        metadata != null ? metadata : buildMetadata(reader),
        reader.columns(),
        reader.decoders(),
        reader.ruleOrder(),
        reader.rowCount(),
        null,
        resource,
        indexes,
        dictionary,
        options.isIncludeTestColumns());
  }

  /** Reconstructs ruleset metadata from a parsed artifact (used when loading from a file). */
  private RulesetMetadata buildMetadata(BinaryArtifactReader reader) {
    List<String> inputColumns = new ArrayList<>();
    List<String> outputColumns = new ArrayList<>();
    String priorityColumn = null;

    for (ColumnDefinition col : reader.columns()) {
      if (col.operator() == Operator.PRIORITY) {
        priorityColumn = col.name();
      } else if (col.isOutput()) {
        outputColumns.add(col.name());
      } else if (col.isInput()) {
        inputColumns.add(col.name());
      }
    }

    return new RulesetMetadata(
        reader.rowCount(), inputColumns, outputColumns, priorityColumn, reader.artifactKind());
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
