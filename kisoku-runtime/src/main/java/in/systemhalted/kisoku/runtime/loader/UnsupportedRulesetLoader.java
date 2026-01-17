package in.systemhalted.kisoku.runtime.loader;

import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.loading.LoadOptions;
import in.systemhalted.kisoku.api.loading.LoadedRuleset;
import in.systemhalted.kisoku.api.loading.RulesetLoader;

/** Placeholder loader that throws until a real implementation is provided. */
public final class UnsupportedRulesetLoader implements RulesetLoader {
  @Override
  public LoadedRuleset load(CompiledRuleset compiled, LoadOptions options) {
    throw new UnsupportedOperationException("Ruleset loading is not implemented yet.");
  }
}
