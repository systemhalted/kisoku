package in.systemhalted.kisoku.api;

public interface RulesetCompiler {
    CompiledRuleset compile(DecisionTableSource source, CompileOptions options);
}
