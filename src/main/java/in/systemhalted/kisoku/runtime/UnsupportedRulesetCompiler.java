package in.systemhalted.kisoku.runtime;

import in.systemhalted.kisoku.api.CompileOptions;
import in.systemhalted.kisoku.api.CompiledRuleset;
import in.systemhalted.kisoku.api.DecisionTableSource;
import in.systemhalted.kisoku.api.RulesetCompiler;

/** Placeholder compiler that throws until a real implementation is provided. */
public final class UnsupportedRulesetCompiler implements RulesetCompiler {
  @Override
  public CompiledRuleset compile(DecisionTableSource source, CompileOptions options) {
    throw new UnsupportedOperationException("Ruleset compilation is not implemented yet.");
  }
}
