package in.systemhalted.kisoku.api.compilation;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.RulesetMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A compiled, serialized decision table artifact. */
public interface CompiledRuleset {
  ArtifactKind kind();

  RulesetMetadata metadata();

  /**
   * The serialized artifact as one array.
   *
   * <p>Compiled artifacts are file-backed and may exceed what a single array can hold; this
   * materializes the file and fails for artifacts over 2 GB. Prefer {@link #writeTo(Path)} plus
   * {@link in.systemhalted.kisoku.api.loading.RulesetLoader#load(Path,
   * in.systemhalted.kisoku.api.loading.LoadOptions)}, or loading the compiled ruleset directly,
   * both of which stream or map instead.
   *
   * @return the artifact bytes
   */
  byte[] bytes();

  /**
   * Serializes this artifact to a file. The file is a self-describing artifact that can later be
   * loaded via {@link in.systemhalted.kisoku.api.loading.RulesetLoader#load(Path,
   * in.systemhalted.kisoku.api.loading.LoadOptions)}, which can memory-map it without holding the
   * whole artifact on the heap.
   *
   * @param target the file to write (created or overwritten)
   * @throws IOException if the file cannot be written
   */
  default void writeTo(Path target) throws IOException {
    Files.write(target, bytes());
  }
}
