package in.systemhalted.kisoku.api;

/** Loads a compiled ruleset into an immutable, evaluation-ready form. */
public interface RulesetLoader {
  LoadedRuleset load(CompiledRuleset compiled, LoadOptions options);
}
