package in.systemhalted.kisoku.api;

public interface CompiledRuleset {
    ArtifactKind kind();

    RulesetMetadata metadata();

    byte[] bytes();
}
