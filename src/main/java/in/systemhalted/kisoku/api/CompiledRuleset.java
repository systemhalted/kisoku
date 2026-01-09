package in.systemhalted.kisoku.api;

/** A compiled, serialized decision table artifact. */
public interface CompiledRuleset {
  ArtifactKind kind();

  RulesetMetadata metadata();

  byte[] bytes();
}
