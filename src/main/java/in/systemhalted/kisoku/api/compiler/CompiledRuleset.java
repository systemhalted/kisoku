package in.systemhalted.kisoku.api.compiler;

import in.systemhalted.kisoku.api.model.ArtifactKind;
import in.systemhalted.kisoku.api.model.RulesetMetadata;

/** A compiled, serialized decision table artifact. */
public interface CompiledRuleset {
  ArtifactKind kind();

  RulesetMetadata metadata();

  byte[] bytes();
}
