package in.systemhalted.kisoku.runtime;

import in.systemhalted.kisoku.api.compiler.CompileOptions;
import in.systemhalted.kisoku.api.compiler.CompiledRuleset;
import in.systemhalted.kisoku.api.compiler.RulesetCompiler;
import in.systemhalted.kisoku.api.model.DecisionTableSource;

/** Placeholder compiler that throws until a real implementation is provided. */
public final class UnsupportedRulesetCompiler implements RulesetCompiler {
  @Override
  public CompiledRuleset compile(DecisionTableSource source, CompileOptions options) {
    throw new UnsupportedOperationException("Ruleset compilation is not implemented yet.");
  }
}
