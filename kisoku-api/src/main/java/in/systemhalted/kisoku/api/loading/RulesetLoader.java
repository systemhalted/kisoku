package in.systemhalted.kisoku.api.loading;

import in.systemhalted.kisoku.api.compilation.CompiledRuleset;

/** Loads a compiled ruleset into an immutable, evaluation-ready form. */
public interface RulesetLoader {
  LoadedRuleset load(CompiledRuleset compiled, LoadOptions options);
}
