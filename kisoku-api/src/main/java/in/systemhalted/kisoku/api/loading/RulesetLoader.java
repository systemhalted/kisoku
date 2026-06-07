package in.systemhalted.kisoku.api.loading;

import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import java.io.IOException;
import java.nio.file.Path;

/** Loads a compiled ruleset into an immutable, evaluation-ready form. */
public interface RulesetLoader {
  LoadedRuleset load(CompiledRuleset compiled, LoadOptions options);

  /**
   * Loads a ruleset directly from an artifact file previously written by {@link
   * CompiledRuleset#writeTo(Path)}.
   *
   * <p>With {@link LoadOptions#memoryMap()} this allows the loader to memory-map the file rather
   * than holding the whole artifact on the heap, which is how large tables stay within the heap
   * budget.
   *
   * @param artifact path to a compiled artifact file
   * @param options load options
   * @return the loaded, evaluation-ready ruleset
   * @throws IOException if the artifact cannot be read
   * @throws UnsupportedOperationException if this loader does not support file-based loading
   */
  default LoadedRuleset load(Path artifact, LoadOptions options) throws IOException {
    throw new UnsupportedOperationException("File-based loading is not supported by this loader");
  }
}
