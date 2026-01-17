package in.systemhalted.kisoku.api.compilation;

import in.systemhalted.kisoku.api.DecisionTableSource;

/** Compiles a decision table source into a serialized ruleset artifact. */
public interface RulesetCompiler {
  CompiledRuleset compile(DecisionTableSource source, CompileOptions options);
}
