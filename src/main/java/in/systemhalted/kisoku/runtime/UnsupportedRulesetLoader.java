package in.systemhalted.kisoku.runtime;

import in.systemhalted.kisoku.api.CompiledRuleset;
import in.systemhalted.kisoku.api.LoadOptions;
import in.systemhalted.kisoku.api.LoadedRuleset;
import in.systemhalted.kisoku.api.RulesetLoader;

/** Placeholder loader that throws until a real implementation is provided. */
public final class UnsupportedRulesetLoader implements RulesetLoader {
  @Override
  public LoadedRuleset load(CompiledRuleset compiled, LoadOptions options) {
    throw new UnsupportedOperationException("Ruleset loading is not implemented yet.");
  }
}
