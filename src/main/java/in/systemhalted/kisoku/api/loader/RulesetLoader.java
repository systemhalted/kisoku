package in.systemhalted.kisoku.api.loader;

import in.systemhalted.kisoku.api.compiler.CompiledRuleset;

/** Loads a compiled ruleset into an immutable, evaluation-ready form. */
public interface RulesetLoader {
  LoadedRuleset load(CompiledRuleset compiled, LoadOptions options);
}
