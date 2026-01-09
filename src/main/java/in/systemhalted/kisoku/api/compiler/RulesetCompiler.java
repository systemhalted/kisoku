package in.systemhalted.kisoku.api.compiler;

import in.systemhalted.kisoku.api.model.DecisionTableSource;

/** Compiles a decision table source into a serialized ruleset artifact. */
public interface RulesetCompiler {
  CompiledRuleset compile(DecisionTableSource source, CompileOptions options);
}
