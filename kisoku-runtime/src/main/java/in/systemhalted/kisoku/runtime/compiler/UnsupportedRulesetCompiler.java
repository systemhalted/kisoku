package in.systemhalted.kisoku.runtime.compiler;

import in.systemhalted.kisoku.api.DecisionTableSource;
import in.systemhalted.kisoku.api.compilation.CompileOptions;
import in.systemhalted.kisoku.api.compilation.CompiledRuleset;
import in.systemhalted.kisoku.api.compilation.RulesetCompiler;

/** Placeholder compiler that throws until a real implementation is provided. */
public final class UnsupportedRulesetCompiler implements RulesetCompiler {
  @Override
  public CompiledRuleset compile(DecisionTableSource source, CompileOptions options) {
    throw new UnsupportedOperationException("Ruleset compilation is not implemented yet.");
  }
}
