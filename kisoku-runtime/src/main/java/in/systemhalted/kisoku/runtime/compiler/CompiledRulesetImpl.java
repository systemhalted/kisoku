package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File-backed implementation of CompiledRuleset.
 *
 * <p>The compiler streams the artifact to a temporary file, so a compiled ruleset never needs to
 * exist as one heap array - artifacts may exceed 2 GB. {@link #bytes()} materializes the file for
 * callers that want the in-memory form and fails for artifacts too large for a single array; {@link
 * #writeTo(Path)} is a file copy; the loader memory-maps {@link #artifactPath()} directly.
 *
 * <p>Public (not exported by the module) so the loader package can reach {@link #artifactPath()}.
 */
public final class CompiledRulesetImpl implements CompiledRuleset {
  private final ArtifactKind kind;
  private final RulesetMetadata metadata;
  private final Path artifactPath;

  CompiledRulesetImpl(ArtifactKind kind, RulesetMetadata metadata, Path artifactPath) {
    this.kind = kind;
    this.metadata = metadata;
    this.artifactPath = artifactPath;
  }

  @Override
  public ArtifactKind kind() {
    return kind;
  }

  @Override
  public RulesetMetadata metadata() {
    return metadata;
  }

  @Override
  public byte[] bytes() {
    try {
      long size = Files.size(artifactPath);
      if (size > Integer.MAX_VALUE - 8) {
        throw new IllegalStateException(
            "Artifact is "
                + size
                + " bytes, too large for byte[]; use writeTo(Path) and"
                + " load(Path) instead");
      }
      return Files.readAllBytes(artifactPath);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read compiled artifact", e);
    }
  }

  @Override
  public void writeTo(Path target) throws IOException {
    Files.copy(artifactPath, target, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * The temporary file holding the serialized artifact.
   *
   * @return the artifact path (valid for the JVM's lifetime; deleted on exit)
   */
  public Path artifactPath() {
    return artifactPath;
  }
}
