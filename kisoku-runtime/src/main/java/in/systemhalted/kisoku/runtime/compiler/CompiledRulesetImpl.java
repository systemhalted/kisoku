package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.RulesetMetadata;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import java.util.Arrays;

/** Immutable implementation of CompiledRuleset holding the compiled artifact. */
final class CompiledRulesetImpl implements CompiledRuleset {
  private final ArtifactKind kind;
  private final RulesetMetadata metadata;
  private final byte[] bytes;

  CompiledRulesetImpl(ArtifactKind kind, RulesetMetadata metadata, byte[] bytes) {
    this.kind = kind;
    this.metadata = metadata;
    this.bytes = Arrays.copyOf(bytes, bytes.length);
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
    return Arrays.copyOf(bytes, bytes.length);
  }
}
