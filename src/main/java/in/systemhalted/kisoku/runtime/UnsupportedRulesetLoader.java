package in.systemhalted.kisoku.runtime;

import in.systemhalted.kisoku.api.compiler.CompiledRuleset;
import in.systemhalted.kisoku.api.loader.LoadOptions;
import in.systemhalted.kisoku.api.loader.LoadedRuleset;
import in.systemhalted.kisoku.api.loader.RulesetLoader;

/** Placeholder loader that throws until a real implementation is provided. */
public final class UnsupportedRulesetLoader implements RulesetLoader {
  @Override
  public LoadedRuleset load(CompiledRuleset compiled, LoadOptions options) {
    throw new UnsupportedOperationException("Ruleset loading is not implemented yet.");
  }
}
