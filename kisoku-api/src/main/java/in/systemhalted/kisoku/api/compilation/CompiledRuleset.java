package in.systemhalted.kisoku.api.compilation;

import in.systemhalted.kisoku.api.ArtifactKind;
import in.systemhalted.kisoku.api.RulesetMetadata;

/** A compiled, serialized decision table artifact. */
public interface CompiledRuleset {
  ArtifactKind kind();

  RulesetMetadata metadata();

  byte[] bytes();
}
