package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Loads compiled decision table artifacts into memory for evaluation.
 *
 * <p>Supports two loading modes:
 *
 * <ul>
 *   <li>On-heap: Copies data into Java arrays for fastest access
 *   <li>Memory-mapped: Uses direct ByteBuffer for lower memory footprint
 * </ul>
 */
public final class CsvRulesetLoader implements RulesetLoader {

  @Override
  public LoadedRuleset load(CompiledRuleset compiled, LoadOptions options) {
    byte[] bytes = compiled.bytes();

    if (options.isMemoryMap()) {
      return loadMemoryMapped(bytes, compiled);
    } else {
      return loadOnHeap(bytes, compiled);
    }
  }

  private LoadedRuleset loadOnHeap(byte[] bytes, CompiledRuleset compiled) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    BinaryArtifactReader reader = BinaryArtifactReader.read(buffer);

    return new LoadedRulesetImpl(
        compiled.metadata(),
        reader.columns(),
        reader.decoders(),
        reader.ruleOrder(),
        null // No direct buffer to clean up
        );
  }

  private LoadedRuleset loadMemoryMapped(byte[] bytes, CompiledRuleset compiled) {
    // For true memory-mapping, we'd need the artifact on disk
    // Since CompiledRuleset provides bytes[], we create a direct ByteBuffer
    ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
    direct.put(bytes);
    direct.flip();
    direct.order(ByteOrder.BIG_ENDIAN);

    BinaryArtifactReader reader = BinaryArtifactReader.read(direct);

    return new LoadedRulesetImpl(
        compiled.metadata(),
        reader.columns(),
        reader.decoders(),
        reader.ruleOrder(),
        direct // Keep reference for potential cleanup
        );
  }
}
