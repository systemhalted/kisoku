package in.systemhalted.kisoku.api;

public interface RulesetLoader {
    LoadedRuleset load(CompiledRuleset compiled, LoadOptions options);
}
