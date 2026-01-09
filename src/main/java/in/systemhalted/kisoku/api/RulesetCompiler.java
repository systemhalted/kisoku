package in.systemhalted.kisoku.api;

/**
 * Compiles a decision table source into a serialized ruleset artifact.
 */
public interface RulesetCompiler {
  CompiledRuleset compile(DecisionTableSource source, CompileOptions options);
}
